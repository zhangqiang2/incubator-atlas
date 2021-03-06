/*
 * Copyright 2012-2013 Aurelius LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.thinkaurelius.titan.graphdb.query.graph;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.*;
import com.thinkaurelius.titan.core.*;
import com.thinkaurelius.titan.core.attribute.Cmp;
import com.thinkaurelius.titan.core.Cardinality;
import com.thinkaurelius.titan.core.schema.SchemaStatus;
import com.thinkaurelius.titan.core.schema.TitanSchemaType;
import com.thinkaurelius.titan.graphdb.database.IndexSerializer;
import com.thinkaurelius.titan.graphdb.internal.ElementCategory;
import com.thinkaurelius.titan.graphdb.internal.InternalRelationType;
import com.thinkaurelius.titan.graphdb.internal.OrderList;
import com.thinkaurelius.titan.graphdb.query.*;
import com.thinkaurelius.titan.graphdb.query.condition.*;
import com.thinkaurelius.titan.graphdb.transaction.StandardTitanTx;
import com.thinkaurelius.titan.graphdb.types.*;
import com.thinkaurelius.titan.graphdb.types.system.ImplicitKey;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;

/**
 *
 * Builds a {@link TitanGraphQuery}, optimizes the query and compiles the result into a {@link GraphCentricQuery} which
 * is then executed through a {@link QueryProcessor}.
 * This class from titan-0.5.4 has no major changes except adding a few logs for debugging index usage
 *
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public class GraphCentricQueryBuilder implements TitanGraphQuery<GraphCentricQueryBuilder> {

    private static final Logger log = LoggerFactory.getLogger(GraphCentricQueryBuilder.class);

    /**
     * Transaction in which this query is executed.
     */
    private final StandardTitanTx tx;
    /**
     * Serializer used to serialize the query conditions into backend queries.
     */
    private final IndexSerializer serializer;
    /**
     * The constraints added to this query. None by default.
     */
    private List<PredicateCondition<String, TitanElement>> constraints;
    /**
     * The order in which the elements should be returned. None by default.
     */
    private OrderList orders = new OrderList();
    /**
     * The limit of this query. No limit by default.
     */
    private int limit = Query.NO_LIMIT;

    public GraphCentricQueryBuilder(StandardTitanTx tx, IndexSerializer serializer) {
        log.debug("Loaded shaded version of class GraphCentricQueryBuilder");
        Preconditions.checkNotNull(tx);
        Preconditions.checkNotNull(serializer);
        this.tx = tx;
        this.serializer = serializer;
        this.constraints = new ArrayList<PredicateCondition<String, TitanElement>>(5);
    }

    /* ---------------------------------------------------------------
     * Query Construction
	 * ---------------------------------------------------------------
	 */

    private GraphCentricQueryBuilder has(String key, TitanPredicate predicate, Object condition) {
        Preconditions.checkNotNull(key);
        Preconditions.checkNotNull(predicate);
        Preconditions.checkArgument(predicate.isValidCondition(condition), "Invalid condition: %s", condition);
        constraints.add(new PredicateCondition<String, TitanElement>(key, predicate, condition));
        return this;
    }

    @Override
    public GraphCentricQueryBuilder has(String key, com.tinkerpop.blueprints.Predicate predicate, Object condition) {
        Preconditions.checkNotNull(key);
        TitanPredicate titanPredicate = TitanPredicate.Converter.convert(predicate);
        return has(key, titanPredicate, condition);
    }

    @Override
    public GraphCentricQueryBuilder has(PropertyKey key, TitanPredicate predicate, Object condition) {
        Preconditions.checkNotNull(key);
        Preconditions.checkNotNull(predicate);
        return has(key.getName(), predicate, condition);
    }

    @Override
    public GraphCentricQueryBuilder has(String key) {
        return has(key, Cmp.NOT_EQUAL, (Object) null);
    }

    @Override
    public GraphCentricQueryBuilder hasNot(String key) {
        return has(key, Cmp.EQUAL, (Object) null);
    }

    @Override
    @Deprecated
    public <T extends Comparable<T>> GraphCentricQueryBuilder has(String s, T t, Compare compare) {
        return has(s, compare, t);
    }

    @Override
    public GraphCentricQueryBuilder has(String key, Object value) {
        return has(key, Cmp.EQUAL, value);
    }

    @Override
    public GraphCentricQueryBuilder hasNot(String key, Object value) {
        return has(key, Cmp.NOT_EQUAL, value);
    }

    @Override
    public <T extends Comparable<?>> GraphCentricQueryBuilder interval(String s, T t1, T t2) {
        has(s, Cmp.GREATER_THAN_EQUAL, t1);
        return has(s, Cmp.LESS_THAN, t2);
    }

    @Override
    public GraphCentricQueryBuilder limit(final int limit) {
        Preconditions.checkArgument(limit >= 0, "Non-negative limit expected: %s", limit);
        this.limit = limit;
        return this;
    }

    @Override
    public GraphCentricQueryBuilder orderBy(String key, Order order) {
        Preconditions.checkArgument(tx.containsPropertyKey(key), "Provided key does not exist: %s", key);
        return orderBy(tx.getPropertyKey(key), order);
    }

    @Override
    public GraphCentricQueryBuilder orderBy(PropertyKey key, Order order) {
        Preconditions.checkArgument(key!=null && order!=null, "Need to specify and key and an order");
        Preconditions.checkArgument(Comparable.class.isAssignableFrom(key.getDataType()), 
            "Can only order on keys with comparable data type. [%s] has datatype [%s]", key.getName(), key.getDataType());
        Preconditions.checkArgument(key.getCardinality()== Cardinality.SINGLE, "Ordering is undefined on multi-valued key [%s]", key.getName());
        Preconditions.checkArgument(!orders.containsKey(key));
        orders.add(key, order);
        return this;
    }

    /* ---------------------------------------------------------------
     * Query Execution
	 * ---------------------------------------------------------------
	 */

    @Override
    public Iterable<Vertex> vertices() {
        GraphCentricQuery query = constructQuery(ElementCategory.VERTEX);
        return Iterables.filter(new QueryProcessor<GraphCentricQuery, TitanElement, JointIndexQuery>(query, tx.elementProcessor), Vertex.class);
    }

    @Override
    public Iterable<Edge> edges() {
        GraphCentricQuery query = constructQuery(ElementCategory.EDGE);
        return Iterables.filter(new QueryProcessor<GraphCentricQuery, TitanElement, JointIndexQuery>(query, tx.elementProcessor), Edge.class);
    }

    @Override
    public Iterable<TitanProperty> properties() {
        GraphCentricQuery query = constructQuery(ElementCategory.PROPERTY);
        return Iterables.filter(new QueryProcessor<GraphCentricQuery, TitanElement, JointIndexQuery>(query, tx.elementProcessor), TitanProperty.class);
    }

    private QueryDescription describe(ElementCategory category) {
        return new StandardQueryDescription(1, constructQuery(category));
    }

    public QueryDescription describeForVertices() {
        return describe(ElementCategory.VERTEX);
    }

    public QueryDescription describeForEdges() {
        return describe(ElementCategory.EDGE);
    }

    public QueryDescription describeForProperties() {
        return describe(ElementCategory.PROPERTY);
    }

    /* ---------------------------------------------------------------
     * Query Construction
	 * ---------------------------------------------------------------
	 */

    private static final int DEFAULT_NO_LIMIT = 1000;
    private static final int MAX_BASE_LIMIT = 20000;
    private static final int HARD_MAX_LIMIT = 100000;

    private static final double EQUAL_CONDITION_SCORE = 4;
    private static final double OTHER_CONDITION_SCORE = 1;
    private static final double ORDER_MATCH = 2;
    private static final double ALREADY_MATCHED_ADJUSTOR = 0.1;
    private static final double CARDINALITY_SINGE_SCORE = 1000;
    private static final double CARDINALITY_OTHER_SCORE = 1000;

    public GraphCentricQuery constructQuery(final ElementCategory resultType) {
        Preconditions.checkNotNull(resultType);
        if (limit == 0) return GraphCentricQuery.emptyQuery(resultType);

        //Prepare constraints
        And<TitanElement> conditions = QueryUtil.constraints2QNF(tx, constraints);
        if (conditions == null) return GraphCentricQuery.emptyQuery(resultType);

        //Prepare orders
        orders.makeImmutable();
        if (orders.isEmpty()) orders = OrderList.NO_ORDER;

        //Compile all indexes that cover at least one of the query conditions
        final Set<IndexType> indexCandidates = new HashSet<IndexType>();
        ConditionUtil.traversal(conditions, new Predicate<Condition<TitanElement>>() {
            @Override
            public boolean apply(@Nullable Condition<TitanElement> condition) {
                if (condition instanceof PredicateCondition) {
                    RelationType type = ((PredicateCondition<RelationType, TitanElement>)condition).getKey();
                    Preconditions.checkArgument(type!=null && type.isPropertyKey());
                    Iterables.addAll(indexCandidates, Iterables.filter(((InternalRelationType) type).getKeyIndexes(), new Predicate<IndexType>() {
                        @Override
                        public boolean apply(@Nullable IndexType indexType) {
                            return indexType.getElement()==resultType;
                        }
                    }));
                }
                return true;
            }
        });

        /*
        Determine the best join index query to answer this query:
        Iterate over all potential indexes (as compiled above) and compute a score based on how many clauses
        this index covers. The index with the highest score (as long as it covers at least one additional clause)
        is picked and added to the joint query for as long as such exist.
         */
        JointIndexQuery jointQuery = new JointIndexQuery();
        boolean isSorted = orders.isEmpty();
        Set<Condition> coveredClauses = Sets.newHashSet();
        while (true) {
            IndexType bestCandidate = null;
            double candidateScore = 0.0;
            Set<Condition> candidateSubcover = null;
            boolean candidateSupportsSort = false;
            Object candidateSubcondition = null;

            for (IndexType index : indexCandidates) {
                Set<Condition> subcover = Sets.newHashSet();
                Object subcondition;
                boolean supportsSort = orders.isEmpty();
                //Check that this index actually applies in case of a schema constraint
                if (index.hasSchemaTypeConstraint()) {
                    TitanSchemaType type = index.getSchemaTypeConstraint();
                    Map.Entry<Condition, Collection<Object>> equalCon = getEqualityConditionValues(conditions, ImplicitKey.LABEL);
                    if (equalCon==null) continue;
                    Collection<Object> labels = equalCon.getValue();
                    assert labels.size()>=1;
                    if (labels.size()>1) {
                        log.warn("The query optimizer currently does not support multiple label constraints in query: {}", this);
                        continue;
                    }
                    if (!type.getName().equals((String)Iterables.getOnlyElement(labels))) continue;
                    subcover.add(equalCon.getKey());
                }

                if (index.isCompositeIndex()) {
                    subcondition = indexCover((CompositeIndexType) index, conditions, subcover);
                } else {
                    subcondition = indexCover((MixedIndexType) index, conditions, serializer, subcover);
                    if (coveredClauses.isEmpty() && !supportsSort
                        && indexCoversOrder((MixedIndexType)index, orders)) supportsSort=true;
                }
                if (subcondition==null) continue;
                assert !subcover.isEmpty();
                double score = 0.0;
                boolean coversAdditionalClause = false;
                for (Condition c : subcover) {
                    double s = (c instanceof PredicateCondition && ((PredicateCondition)c).getPredicate()==Cmp.EQUAL)?
                        EQUAL_CONDITION_SCORE:OTHER_CONDITION_SCORE;
                    if (coveredClauses.contains(c)) s=s*ALREADY_MATCHED_ADJUSTOR;
                    else coversAdditionalClause = true;
                    score+=s;
                    if (index.isCompositeIndex())
                        score+=((CompositeIndexType)index).getCardinality()==Cardinality.SINGLE?
                            CARDINALITY_SINGE_SCORE:CARDINALITY_OTHER_SCORE;
                }
                if (supportsSort) score+=ORDER_MATCH;
                if (coversAdditionalClause && score>candidateScore) {
                    candidateScore=score;
                    bestCandidate=index;
                    candidateSubcover = subcover;
                    candidateSubcondition = subcondition;
                    candidateSupportsSort = supportsSort;
                }
            }
            if (bestCandidate!=null) {
                if (coveredClauses.isEmpty()) isSorted=candidateSupportsSort;
                coveredClauses.addAll(candidateSubcover);

                log.debug("Index chosen for query {} {} " , bestCandidate.isCompositeIndex() ? "COMPOSITE" : "MIXED", coveredClauses);
                if (bestCandidate.isCompositeIndex()) {
                    jointQuery.add((CompositeIndexType)bestCandidate, 
                        serializer.getQuery((CompositeIndexType)bestCandidate, (List<Object[]>)candidateSubcondition));
                } else {
                    jointQuery.add((MixedIndexType)bestCandidate, 
                        serializer.getQuery((MixedIndexType)bestCandidate, (Condition)candidateSubcondition, orders));
                }
            } else {
                break;
            }
            /* TODO: smarter optimization:
            - use in-memory histograms to estimate selectivity of PredicateConditions and filter out low-selectivity ones
                    if they would result in an individual index call (better to filter afterwards in memory)
            - move OR's up and extend GraphCentricQuery to allow multiple JointIndexQuery for proper or'ing of queries
            */
        }

        BackendQueryHolder<JointIndexQuery> query;
        if (!coveredClauses.isEmpty()) {
            int indexLimit = limit == Query.NO_LIMIT ? HARD_MAX_LIMIT : limit;
            if (tx.getGraph().getConfiguration().adjustQueryLimit()) {
                indexLimit = limit == Query.NO_LIMIT ? DEFAULT_NO_LIMIT : Math.min(MAX_BASE_LIMIT, limit);
            }
            indexLimit = Math.min(HARD_MAX_LIMIT, QueryUtil.adjustLimitForTxModifications(tx, coveredClauses.size(), indexLimit));
            jointQuery.setLimit(indexLimit);
            query = new BackendQueryHolder<JointIndexQuery>(jointQuery, coveredClauses.size()==conditions.numChildren(), isSorted, null);
        } else {
            query = new BackendQueryHolder<JointIndexQuery>(new JointIndexQuery(), false, isSorted, null);
        }

        return new GraphCentricQuery(resultType, conditions, orders, query, limit);
    }

    public static final boolean indexCoversOrder(MixedIndexType index, OrderList orders) {
        for (int i = 0; i < orders.size(); i++) {
            if (!index.indexesKey(orders.getKey(i))) return false;
        }
        return true;
    }

    public static List<Object[]> indexCover(final CompositeIndexType index, Condition<TitanElement> condition, Set<Condition> covered) {
        assert QueryUtil.isQueryNormalForm(condition);
        assert condition instanceof And;
        if (index.getStatus()!= SchemaStatus.ENABLED) return null;
        IndexField[] fields = index.getFieldKeys();
        Object[] indexValues = new Object[fields.length];
        Set<Condition> coveredClauses = new HashSet<Condition>(fields.length);
        List<Object[]> indexCovers = new ArrayList<Object[]>(4);

        constructIndexCover(indexValues, 0, fields, condition, indexCovers, coveredClauses);
        if (!indexCovers.isEmpty()) {
            covered.addAll(coveredClauses);
            return indexCovers;
        } else return null;
    }

    private static void constructIndexCover(Object[] indexValues, int position, IndexField[] fields, 
        Condition<TitanElement> condition, 
        List<Object[]> indexCovers, Set<Condition> coveredClauses) {
        if (position>=fields.length) {
            indexCovers.add(indexValues);
        } else {
            IndexField field = fields[position];
            Map.Entry<Condition, Collection<Object>> equalCon = getEqualityConditionValues(condition, field.getFieldKey());
            if (equalCon!=null) {
                coveredClauses.add(equalCon.getKey());
                assert equalCon.getValue().size()>0;
                for (Object value : equalCon.getValue()) {
                    Object[] newValues = Arrays.copyOf(indexValues, fields.length);
                    newValues[position]=value;
                    constructIndexCover(newValues, position+1, fields, condition, indexCovers, coveredClauses);
                }
            } else return;
        }

    }

    private static final Map.Entry<Condition, Collection<Object>> getEqualityConditionValues(Condition<TitanElement> condition, RelationType type) {
        for (Condition c : condition.getChildren()) {
            if (c instanceof Or) {
                Map.Entry<RelationType, Collection> orEqual = QueryUtil.extractOrCondition((Or)c);
                if (orEqual!=null && orEqual.getKey().equals(type) && !orEqual.getValue().isEmpty()) {
                    return new AbstractMap.SimpleImmutableEntry(c, orEqual.getValue());
                }
            } else if (c instanceof PredicateCondition) {
                PredicateCondition<RelationType, TitanRelation> atom = (PredicateCondition)c;
                if (atom.getKey().equals(type) && atom.getPredicate()==Cmp.EQUAL && atom.getValue()!=null) {
                    return new AbstractMap.SimpleImmutableEntry(c, ImmutableList.of(atom.getValue()));
                }
            }

        }
        return null;
    }

    public static final Condition<TitanElement> indexCover(final MixedIndexType index, Condition<TitanElement> condition, 
        final IndexSerializer indexInfo, final Set<Condition> covered) {
        assert QueryUtil.isQueryNormalForm(condition);
        assert condition instanceof And;
        And<TitanElement> subcondition = new And<TitanElement>(condition.numChildren());
        for (Condition<TitanElement> subclause : condition.getChildren()) {
            if (coversAll(index, subclause, indexInfo)) {
                subcondition.add(subclause);
                covered.add(subclause);
            }
        }
        return subcondition.isEmpty()?null:subcondition;
    }

    private static final boolean coversAll(final MixedIndexType index, Condition<TitanElement> condition, IndexSerializer indexInfo) {
        if (condition.getType()==Condition.Type.LITERAL) {
            if (!(condition instanceof  PredicateCondition)) return false;
            PredicateCondition<RelationType, TitanElement> atom = (PredicateCondition) condition;
            if (atom.getValue()==null) return false;

            Preconditions.checkArgument(atom.getKey().isPropertyKey());
            PropertyKey key = (PropertyKey) atom.getKey();
            ParameterIndexField[] fields = index.getFieldKeys();
            ParameterIndexField match = null;
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].getStatus()!= SchemaStatus.ENABLED) continue;
                if (fields[i].getFieldKey().equals(key)) match = fields[i];
            }
            if (match==null) return false;
            return indexInfo.supports(index, match, atom.getPredicate());
        } else {
            for (Condition<TitanElement> child : condition.getChildren()) {
                if (!coversAll(index, child, indexInfo)) return false;
            }
            return true;
        }
    }
}
