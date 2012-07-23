/*
 * Copyright (c) 2010-2011. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.commandhandling.disruptor;

import com.lmax.disruptor.EventHandler;
import org.axonframework.commandhandling.CommandCallback;
import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.RollbackConfiguration;
import org.axonframework.domain.DomainEventStream;
import org.axonframework.domain.EventMessage;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventsourcing.EventSourcedAggregateRoot;
import org.axonframework.eventstore.EventStore;
import org.axonframework.repository.AggregateNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

import static java.lang.String.format;

/**
 * Component of the DisruptorCommandBus that stores and publishes events generated by the command's execution.
 *
 * @author Allard Buijze
 * @since 2.0
 */
public class EventPublisher implements EventHandler<CommandHandlingEntry> {

    private static final Logger logger = LoggerFactory.getLogger(DisruptorCommandBus.class);

    private final EventStore eventStore;
    private final EventBus eventBus;
    private final Executor executor;
    private final RollbackConfiguration rollbackConfiguration;
    private final int segmentId;
    private final Set<Object> blackListedAggregates = new HashSet<Object>();
    private final Map<CommandMessage, Object> failedCreateCommands = new WeakHashMap<CommandMessage, Object>();

    /**
     * Initializes the EventPublisher to publish Events to the given <code>eventStore</code> and <code>eventBus</code>
     * for aggregate of given <code>aggregateType</code>.
     *
     * @param eventStore            The EventStore persisting the generated events
     * @param eventBus              The EventBus to publish events on
     * @param executor              The executor which schedules response reporting
     * @param rollbackConfiguration The configuration that indicates which exceptions should result in a UnitOfWork
     * @param segmentId             The ID of the segment this publisher should handle
     */
    public EventPublisher(EventStore eventStore, EventBus eventBus, Executor executor,
                          RollbackConfiguration rollbackConfiguration, int segmentId) {
        this.eventStore = eventStore;
        this.eventBus = eventBus;
        this.executor = executor;
        this.rollbackConfiguration = rollbackConfiguration;
        this.segmentId = segmentId;
    }

    @SuppressWarnings({"unchecked", "ThrowableResultOfMethodCallIgnored"})
    @Override
    public void onEvent(CommandHandlingEntry entry, long sequence, boolean endOfBatch) throws Exception {
        if (entry.isRecoverEntry()) {
            recoverAggregate(entry);
        } else if (entry.getPublisherId() == segmentId) {
            if (entry.getExceptionResult() instanceof AggregateNotFoundException
                    && failedCreateCommands.remove(entry.getCommand()) == null) {
                // the command failed for the first time
                reschedule(entry);
            } else {
                DisruptorUnitOfWork unitOfWork = entry.getUnitOfWork();
                EventSourcedAggregateRoot aggregate = unitOfWork.getAggregate();
                if (aggregate != null && blackListedAggregates.contains(aggregate.getIdentifier())) {
                    rejectExecution(entry, unitOfWork, entry.getAggregateIdentifier());
                } else {
                    processPublication(entry, unitOfWork, aggregate);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void reschedule(CommandHandlingEntry entry) {
        failedCreateCommands.put(entry.getCommand(), logger);
        executor.execute(new ReportResultTask(
                entry.getCallback(), null,
                new AggregateStateCorruptedException(
                        entry.getAggregateIdentifier(), "Rescheduling command for execution. "
                        + "It was executed against a potentially recently created command")));
    }

    private void recoverAggregate(CommandHandlingEntry entry) {
        if (blackListedAggregates.remove(entry.getAggregateIdentifier())) {
            logger.info("Reset notification for {} received. The aggregate is removed from the blacklist",
                        entry.getAggregateIdentifier());
        }
    }

    @SuppressWarnings("unchecked")
    private void rejectExecution(CommandHandlingEntry entry, DisruptorUnitOfWork unitOfWork,
                                 Object aggregateIdentifier) {
        executor.execute(new ReportResultTask(
                entry.getCallback(), null,
                new AggregateStateCorruptedException(
                        unitOfWork.getAggregate(),
                        format("Aggregate %s has been blacklisted and will be ignored until "
                                       + "its state has been recovered.",
                               aggregateIdentifier))));
    }

    @SuppressWarnings("unchecked")
    private void processPublication(CommandHandlingEntry entry, DisruptorUnitOfWork unitOfWork,
                                    EventSourcedAggregateRoot aggregate) {
        invokeInterceptorChain(entry);
        Throwable exceptionResult = entry.getExceptionResult();
        try {
            if (exceptionResult != null && rollbackConfiguration.rollBackOn(exceptionResult)) {
                exceptionResult = performRollback(unitOfWork, entry.getAggregateIdentifier(), exceptionResult);
            } else {
                exceptionResult = performCommit(unitOfWork, aggregate, exceptionResult);
            }
        } finally {
            unitOfWork.onCleanup();
        }
        if (exceptionResult != null || entry.getCallback().hasDelegate()) {
            executor.execute(new ReportResultTask(entry.getCallback(), entry.getResult(), exceptionResult));
        }
    }

    private void invokeInterceptorChain(CommandHandlingEntry entry) {
        try {
            entry.setResult(entry.getPublisherInterceptorChain().proceed(entry.getCommand()));
        } catch (Throwable throwable) {
            entry.setExceptionResult(throwable);
        }
    }

    private Throwable performRollback(DisruptorUnitOfWork unitOfWork, Object aggregateIdentifier,
                                      Throwable exceptionResult) {
        unitOfWork.onRollback(exceptionResult);
        if (aggregateIdentifier != null) {
            exceptionResult = notifyBlacklisted(unitOfWork, aggregateIdentifier, exceptionResult);
        }
        return exceptionResult;
    }

    private Throwable performCommit(DisruptorUnitOfWork unitOfWork, EventSourcedAggregateRoot aggregate,
                                    Throwable exceptionResult) {
        unitOfWork.onPrepareCommit();
        try {
            if (exceptionResult != null && rollbackConfiguration.rollBackOn(exceptionResult)) {
                unitOfWork.rollback(exceptionResult);
            } else {
                storeAndPublish(unitOfWork);
                unitOfWork.onAfterCommit();
            }
        } catch (Exception e) {
            exceptionResult = notifyBlacklisted(unitOfWork, aggregate.getIdentifier(), e);
        }
        return exceptionResult;
    }

    private void storeAndPublish(DisruptorUnitOfWork unitOfWork) {
        DomainEventStream eventsToStore = unitOfWork.getEventsToStore();
        eventStore.appendEvents(unitOfWork.getAggregateType(), eventsToStore);
        List<EventMessage> eventMessages = unitOfWork.getEventsToPublish();
        EventMessage[] eventsToPublish = eventMessages.toArray(new EventMessage[eventMessages.size()]);
        if (eventBus != null) {
            eventBus.publish(eventsToPublish);
        }
    }

    private Throwable notifyBlacklisted(DisruptorUnitOfWork unitOfWork, Object aggregateIdentifier,
                                        Throwable cause) {
        Throwable exceptionResult;
        blackListedAggregates.add(aggregateIdentifier);
        exceptionResult = new AggregateBlacklistedException(
                aggregateIdentifier,
                format("Aggregate %s state corrupted. "
                               + "Blacklisting the aggregate until a reset message has been received",
                       aggregateIdentifier), cause);
        unitOfWork.onRollback(exceptionResult);
        return exceptionResult;
    }

    private class ReportResultTask<R> implements Runnable {

        private final CommandCallback<R> callback;
        private final R result;
        private final Throwable exceptionResult;

        public ReportResultTask(CommandCallback<R> callback, R result, Throwable exceptionResult) {
            this.callback = callback;
            this.result = result;
            this.exceptionResult = exceptionResult;
        }

        @Override
        public void run() {
            if (exceptionResult != null) {
                callback.onFailure(exceptionResult);
            } else {
                callback.onSuccess(result);
            }
        }
    }
}
