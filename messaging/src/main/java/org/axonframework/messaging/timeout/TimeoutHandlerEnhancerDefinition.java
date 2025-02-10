/*
 * Copyright (c) 2010-2025. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.messaging.timeout;

import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.deadline.DeadlineMessage;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.messaging.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.annotation.MessageHandlingMember;
import org.axonframework.queryhandling.QueryMessage;

import javax.annotation.Nonnull;

/**
 * Inspects message handler and wraps it in a {@link TimeoutWrappedMessageHandlingMember} if the handler should have a
 * timeout.
 * <p>
 * The timeout is determined by the {@link HandlerTimeoutConfiguration} and the
 * {@link org.axonframework.messaging.MessageHandlerTimeout} annotation on the message handler method. The annotation
 * takes precedence over the configuration.
 *
 * @author Mitchell Herrijgers
 * @see TimeoutWrappedMessageHandlingMember
 * @see HandlerTimeoutConfiguration
 * @since 4.11
 */
public class TimeoutHandlerEnhancerDefinition implements HandlerEnhancerDefinition {

    private final HandlerTimeoutConfiguration configuration;

    public TimeoutHandlerEnhancerDefinition(HandlerTimeoutConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public <T> MessageHandlingMember<T> wrapHandler(@Nonnull MessageHandlingMember<T> original) {
        HandlerTimeoutConfiguration.TimeoutForType config = getConfigurationForMember(original);
        if (config == null) {
            // Unknown type of message. Don't enhance the handler.
            return original;
        }

        // We need to calculate the threshold and interval values based on configuration and annotation values.
        int timeout = getAttribute(original, "timeout", config.getTimeoutMs());
        int warning = getAttribute(original, "warningThreshold", config.getWarningThresholdMs());
        int warningInterval = getAttribute(original, "warningInterval", config.getWarningIntervalMs());

        if (timeout < 0 && warning < 0) {
            // No timeout configuration found. Don't enhance the handler.
            return original;
        }

        return new TimeoutWrappedMessageHandlingMember<>(original, timeout, warning, warningInterval);
    }

    /**
     * Gets the attribute or the {@link org.axonframework.messaging.MessageHandlerTimeout} annotation or the default
     * value if the attribute is not present or invalid.
     *
     * @param original The original message handler
     * @param name     The name of the attribute
     * @param fallback The default value
     * @return The attribute value or the default value
     */
    private int getAttribute(MessageHandlingMember<?> original, String name, int fallback) {
        return (int) original.attribute("MessageHandlerTimeout." + name)
                             .filter(i -> ((int) i) >= 0)
                             .orElse(fallback);
    }

    /**
     * Gets the configuration for the given message handler, based on the message type it can handle.
     *
     * @param original The original message handler
     * @return The configuration for the message handler
     */
    private HandlerTimeoutConfiguration.TimeoutForType getConfigurationForMember(
            @Nonnull MessageHandlingMember<?> original) {
        if (original.canHandleMessageType(EventMessage.class)) {
            return configuration.getEvents();
        }
        if (original.canHandleMessageType(CommandMessage.class)) {
            return configuration.getCommands();
        }
        if (original.canHandleMessageType(QueryMessage.class)) {
            return configuration.getQueries();
        }
        if (original.canHandleMessageType(DeadlineMessage.class)) {
            return configuration.getDeadlines();
        }
        return null;
    }
}
