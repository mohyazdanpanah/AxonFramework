/*
 * Copyright (c) 2010-2024. Axon Framework
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

package org.axonframework.spring.serialization.avro;

import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecordBase;
import org.axonframework.serialization.avro.AvroUtil;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Avro schema loader loading schemas embedded into Java classes generated by Avro Java Maven generator, which include
 * the original schema in a static field.
 *
 * @author Simon Zambrovski
 * @author Jan Galinski
 * @since 4.11.0
 */
public class SpecificRecordBaseClasspathAvroSchemaLoader implements ClasspathAvroSchemaLoader {

    private final ClassPathScanningCandidateComponentProvider candidateProvider;

    /**
     * Constructs a new schema loader, using provided {@link ResourceLoader}
     * @param resourceLoader resource loader used to load classes.
     */
    public SpecificRecordBaseClasspathAvroSchemaLoader(ResourceLoader resourceLoader) {
        candidateProvider = new ClassPathScanningCandidateComponentProvider(false);
        candidateProvider.setResourceLoader(resourceLoader);
        candidateProvider.addIncludeFilter(new AssignableTypeFilter(SpecificRecordBase.class));
    }


    @Override
    public List<Schema> load(List<String> packageNames) {
        return packageNames.stream()
                           .map(this::scan)
                           .flatMap(List::stream)
                           .collect(Collectors.toList());
    }

    private List<Schema> scan(String packageName) {
        Set<BeanDefinition> candidates = candidateProvider.findCandidateComponents(packageName);
        return candidates.stream()
                         .map(candidate -> {
                             try {
                                 Class<?> clazz = Class.forName(candidate.getBeanClassName());
                                 if (SpecificRecordBase.class.isAssignableFrom(clazz)) {
                                     @SuppressWarnings("unchecked")
                                     Class<SpecificRecordBase> specificRecordBaseClass = (Class<SpecificRecordBase>) clazz;
                                     return AvroUtil.getClassSchemaChecked(specificRecordBaseClass);
                                 } else {
                                     return null;
                                 }
                             } catch (Exception e) {
                                 return null;
                             }
                         }).filter(Objects::nonNull)
                         .collect(Collectors.toList());
    }
}
