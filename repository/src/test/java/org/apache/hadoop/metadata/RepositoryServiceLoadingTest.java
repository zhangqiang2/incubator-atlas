/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.metadata;

import junit.framework.Assert;
import org.apache.hadoop.metadata.repository.graph.GraphService;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Guice;
import org.testng.annotations.Test;

import javax.inject.Inject;

/**
 * Unit test for Guice injector service loading
 *
 * Uses TestNG's Guice annotation to load the necessary modules and inject the
 * objects from Guice
 */
@Guice(modules = RepositoryMetadataModule.class)
public class RepositoryServiceLoadingTest {

    @Inject
    GraphService gs;

    @BeforeClass
    public void setUp() throws Exception {
    }

    @AfterClass
    public void tearDown() throws Exception {
    }

    @Test
    public void testGetGraphService() throws Exception {
        Assert.assertNotNull(gs);
    }
}