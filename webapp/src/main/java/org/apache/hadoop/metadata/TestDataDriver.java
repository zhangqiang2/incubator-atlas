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

import com.google.common.collect.ImmutableList;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import org.apache.hadoop.metadata.typesystem.json.Serialization$;
import org.apache.hadoop.metadata.typesystem.json.TypesSerialization;
import org.apache.hadoop.metadata.typesystem.ITypedReferenceableInstance;
import org.apache.hadoop.metadata.typesystem.Referenceable;
import org.apache.hadoop.metadata.typesystem.Struct;
import org.apache.hadoop.metadata.typesystem.types.AttributeDefinition;
import org.apache.hadoop.metadata.typesystem.types.ClassType;
import org.apache.hadoop.metadata.typesystem.types.DataTypes;
import org.apache.hadoop.metadata.typesystem.types.HierarchicalTypeDefinition;
import org.apache.hadoop.metadata.typesystem.types.Multiplicity;
import org.apache.hadoop.metadata.typesystem.types.StructTypeDefinition;
import org.apache.hadoop.metadata.typesystem.types.TraitType;
import org.apache.hadoop.metadata.typesystem.types.TypeSystem;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.util.Arrays;

import static org.apache.hadoop.metadata.typesystem.types.utils.TypesUtil.createClassTypeDef;
import static org.apache.hadoop.metadata.typesystem.types.utils.TypesUtil.createRequiredAttrDef;
import static org.apache.hadoop.metadata.typesystem.types.utils.TypesUtil.createTraitTypeDef;

public class TestDataDriver {

    private static final Logger LOG = LoggerFactory.getLogger(TestDataDriver.class);

    private static final String DATABASE_TYPE = "hive_database";
    private static final String TABLE_TYPE = "hive_table";

    protected TypeSystem typeSystem;
    protected WebResource service;

    public static void main(String[] args) throws Exception {
        TestDataDriver driver = new TestDataDriver();
        driver.setUp();

        driver.createHiveTypes();
        driver.submitTypes();

        String[][] data = getTestData();
        for (String[] row : data) {
            ITypedReferenceableInstance tableInstance = driver.createHiveTableInstance(
                    row[0], row[1], row[2], row[3], row[4]);
            driver.submitEntity(tableInstance);
        }

        driver.getEntityList();
    }

    private static String[][] getTestData() {
        return new String[][]{
                {"sales_db", "customer_fact", "pii", "serde1", "serde2"},
                {"sales_db", "sales_dim", "dim", "serde1", "serde2"},
                {"sales_db", "product_dim", "dim", "serde1", "serde2"},
                {"sales_db", "time_dim", "dim", "serde1", "serde2"},
                {"reporting_db", "weekly_sales_summary", "summary", "serde1", "serde2"},
                {"reporting_db", "daily_sales_summary", "summary", "serde1", "serde2"},
                {"reporting_db", "monthly_sales_summary", "summary", "serde1", "serde2"},
                {"reporting_db", "quarterly_sales_summary", "summary", "serde1", "serde2"},
                {"reporting_db", "yearly_sales_summary", "summary", "serde1", "serde2"},
        };
    }

    public void setUp() throws Exception {
        typeSystem = TypeSystem.getInstance();
        typeSystem.reset();

        String baseUrl = "http://localhost:21000/";

        DefaultClientConfig config = new DefaultClientConfig();
        Client client = Client.create(config);
        client.resource(UriBuilder.fromUri(baseUrl).build());

        service = client.resource(UriBuilder.fromUri(baseUrl).build());
    }

    public void submitEntity(ITypedReferenceableInstance tableInstance) throws Exception {
        String tableInstanceAsJSON = Serialization$.MODULE$.toJson(tableInstance);
        LOG.debug("tableInstance = " + tableInstanceAsJSON);

        WebResource resource = service
                .path("api/metadata/entities/submit")
                .path(TABLE_TYPE);

        ClientResponse clientResponse = resource
                .accept(MediaType.APPLICATION_JSON)
                .type(MediaType.APPLICATION_JSON)
                .method(HttpMethod.POST, ClientResponse.class, tableInstanceAsJSON);
        assert clientResponse.getStatus() == Response.Status.OK.getStatusCode();
    }

    public void getEntityList() throws Exception {
        ClientResponse clientResponse = service
                .path("api/metadata/entities/list/")
                .path(TABLE_TYPE)
                .accept(MediaType.APPLICATION_JSON)
                .type(MediaType.APPLICATION_JSON)
                .method(HttpMethod.GET, ClientResponse.class);
        assert clientResponse.getStatus() == Response.Status.OK.getStatusCode();

        String responseAsString = clientResponse.getEntity(String.class);
        JSONObject response = new JSONObject(responseAsString);
        final JSONArray list = response.getJSONArray("list");
        System.out.println("list = " + list);
        assert list != null;
        assert list.length() > 0;
    }

    private void createHiveTypes() throws Exception {
        HierarchicalTypeDefinition<ClassType> databaseTypeDefinition =
                createClassTypeDef(DATABASE_TYPE,
                        ImmutableList.<String>of(),
                        createRequiredAttrDef("name", DataTypes.STRING_TYPE),
                        createRequiredAttrDef("description", DataTypes.STRING_TYPE));

        StructTypeDefinition structTypeDefinition =
                new StructTypeDefinition("serdeType",
                        new AttributeDefinition[]{
                                createRequiredAttrDef("name", DataTypes.STRING_TYPE),
                                createRequiredAttrDef("serde", DataTypes.STRING_TYPE)
                        });

        HierarchicalTypeDefinition<ClassType> tableTypeDefinition =
                createClassTypeDef(TABLE_TYPE,
                        ImmutableList.<String>of(),
                        createRequiredAttrDef("name", DataTypes.STRING_TYPE),
                        createRequiredAttrDef("description", DataTypes.STRING_TYPE),
                        createRequiredAttrDef("type", DataTypes.STRING_TYPE),
                        new AttributeDefinition("serde1",
                                "serdeType", Multiplicity.REQUIRED, false, null),
                        new AttributeDefinition("serde2",
                                "serdeType", Multiplicity.REQUIRED, false, null),
                        new AttributeDefinition("database",
                                DATABASE_TYPE, Multiplicity.REQUIRED, true, null));

        HierarchicalTypeDefinition<TraitType> classificationTypeDefinition =
                createTraitTypeDef("classification",
                        ImmutableList.<String>of(),
                        createRequiredAttrDef("tag", DataTypes.STRING_TYPE));

        typeSystem.defineTypes(
                ImmutableList.of(structTypeDefinition),
                ImmutableList.of(classificationTypeDefinition),
                ImmutableList.of(databaseTypeDefinition, tableTypeDefinition));
    }

    private void submitTypes() throws Exception {
        String typesAsJSON = TypesSerialization.toJson(typeSystem,
                Arrays.asList(
                        new String[]{DATABASE_TYPE, TABLE_TYPE, "serdeType", "classification"}));
        sumbitType(typesAsJSON, TABLE_TYPE);
    }

    private void sumbitType(String typesAsJSON, String type) throws JSONException {
        WebResource resource = service
                .path("api/metadata/types/submit")
                .path(type);

        ClientResponse clientResponse = resource
                .accept(MediaType.APPLICATION_JSON)
                .type(MediaType.APPLICATION_JSON)
                .method(HttpMethod.POST, ClientResponse.class, typesAsJSON);
        assert clientResponse.getStatus() == Response.Status.OK.getStatusCode();

        String responseAsString = clientResponse.getEntity(String.class);
        JSONObject response = new JSONObject(responseAsString);
        assert response.get("typeName").equals(type);
        assert response.get("types") != null;
    }

    private ITypedReferenceableInstance createHiveTableInstance(String db,
                                                                String table,
                                                                String trait,
                                                                String serde1,
                                                                String serde2) throws Exception {
        Referenceable databaseInstance = new Referenceable(DATABASE_TYPE);
        databaseInstance.set("name", db);
        databaseInstance.set("description", db + " database");

        Referenceable tableInstance = new Referenceable(TABLE_TYPE, "classification");
        tableInstance.set("name", table);
        tableInstance.set("description", table + " table");
        tableInstance.set("type", "managed");
        tableInstance.set("database", databaseInstance);

        Struct traitInstance = (Struct) tableInstance.getTrait("classification");
        traitInstance.set("tag", trait);

        Struct serde1Instance = new Struct("serdeType");
        serde1Instance.set("name", serde1);
        serde1Instance.set("serde", serde1);
        tableInstance.set("serde1", serde1Instance);

        Struct serde2Instance = new Struct("serdeType");
        serde2Instance.set("name", serde2);
        serde2Instance.set("serde", serde2);
        tableInstance.set("serde2", serde2Instance);

        ClassType tableType = typeSystem.getDataType(ClassType.class, TABLE_TYPE);
        return tableType.convert(tableInstance, Multiplicity.REQUIRED);
    }
}