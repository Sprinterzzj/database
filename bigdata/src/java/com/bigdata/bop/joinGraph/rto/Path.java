package com.bigdata.bop.joinGraph.rto;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.log4j.Logger;

import com.bigdata.bop.BOp;
import com.bigdata.bop.BOpEvaluationContext;
import com.bigdata.bop.BOpIdFactory;
import com.bigdata.bop.BOpUtility;
import com.bigdata.bop.IBindingSet;
import com.bigdata.bop.IConstraint;
import com.bigdata.bop.IPredicate;
import com.bigdata.bop.NV;
import com.bigdata.bop.PipelineOp;
import com.bigdata.bop.engine.IRunningQuery;
import com.bigdata.bop.engine.LocalChunkMessage;
import com.bigdata.bop.engine.QueryEngine;
import com.bigdata.bop.join.PipelineJoin;
import com.bigdata.bop.join.PipelineJoin.PipelineJoinStats;
import com.bigdata.bop.joinGraph.PartitionedJoinGroup;
import com.bigdata.relation.accesspath.ThickAsynchronousIterator;
import com.bigdata.striterator.Dechunkerator;

/**
 * A join path is an ordered sequence of N {@link Vertex vertices} and
 * represents an ordered series of N-1 joins.
 * <p>
 * During exploration, the {@link Path} is used to develop an estimate of the
 * cost of different join paths which explore the {@link Vertex vertices} in a
 * {@link JGraph join graph}, possibly under some set of {@link IConstraint}s.
 * The estimated cost of the join path is developed from a sample of the initial
 * {@link Vertex} followed by the cutoff sample of each join in the join path.
 * Join paths may be re-sampled in successive rounds at a greater sample size in
 * order to improve the accuracy and robustness of the estimated cost for the
 * join path.
 * <p>
 * Each join path reflects a specific history. The cutoff sample for the initial
 * vertex can be shared across join paths since there is no prior history. This
 * is true even when we re-sample the vertex at the start of each round. The
 * cutoff sample for each join reflects the history of joins. It can only be
 * shared with join paths having the same history up to that vertex. For
 * example, the following join paths can share estimates of the vertices A, B,
 * and C but not D or E.
 * 
 * <pre>
 * p1: {A, B, C, E, D}
 * p2: {A, B, C, D, E}
 * </pre>
 * 
 * This is because their histories diverge after the (B,C) join.
 * <p>
 * In each successive round of exploration, each join path is replaced by one or
 * more one-step extensions of that path. The extensions are generated by
 * considering the {@link Vertex vertices} in the join graph which are not yet
 * in use within the join path. The join paths which spanning the same unordered
 * set of vertices in a given round of exploration compete based on their
 * estimated cost. The winner is the join path with the lowest estimated cost.
 * The losers are dropped from further consideration in order to prune the
 * search space. See {@link JGraph} which manages the expansion and competition
 * among join paths.
 * <p>
 * When considering {@link Vertex vertices} which can extend the join path, we
 * first select constrained joins. Only if there are no remaining constrained
 * joins will a join path be extended by an unconstrained join. A constrained
 * join is one which shares a variable with the existing join path. The variable
 * may either be shared directly via the {@link IPredicate}s or indirectly via
 * an {@link IConstraint} which can be evaluated for the {@link Vertex} under
 * consideration given the set of variables which are already known to be bound
 * for the join path. An unconstrained join is one where there are no shared
 * variables and always results in a full cross-product. Unconstrained joins are
 * not chosen unless there are no available constrained joins.
 */
public class Path {

    private static final transient Logger log = Logger.getLogger(Path.class);

    /**
     * An ordered list of the vertices in the {@link Path}.
     */
    final Vertex[] vertices;

    /**
     * An ordered list of the {@link IPredicate}s in the {@link #vertices}. This
     * is computed by the constructor and cached as it is used repeatedly.
     */
    private final IPredicate<?>[] preds;
    
    /**
     * The sample obtained by the step-wise cutoff evaluation of the ordered
     * edges of the path.
     * <p>
     * Note: This sample is generated one edge at a time rather than by
     * attempting the cutoff evaluation of the entire join path (the latter
     * approach does allow us to limit the amount of work to be done to
     * satisfy the cutoff).
     */
    EdgeSample edgeSample;

    /**
     * The cumulative estimated cardinality of the path. This is zero for an
     * empty path. For a path consisting of a single edge, this is the estimated
     * cardinality of that edge. When creating a new path by adding an edge to
     * an existing path, the cumulative cardinality of the new path is the
     * cumulative cardinality of the existing path plus the estimated
     * cardinality of the cutoff join of the new edge given the input sample of
     * the existing path.
     * 
     * @todo Track this per vertex as well as the total for more interesting
     *       traces in showPath(Path). In fact, that is just the VertexSample
     *       for the initial vertex and the EdgeSample for each subsequent
     *       vertex in path order. The EdgeSamples are maintained in a map
     *       managed by JGraph during optimization.
     */
    final public long cumulativeEstimatedCardinality;

    /**
     * Combine the cumulative estimated cost of the source path with the cost of
     * the edge sample and return the cumulative estimated cost of the new path.
     * 
     * @param cumulativeEstimatedCardinality
     *            The cumulative estimated cost of the source path.
     * @param edgeSample
     *            The cost estimate for the cutoff join required to extend the
     *            source path to the new path.
     * @return The cumulative estimated cost of the new path.
     * 
     *         FIXME Figure out how to properly combine/weight the #of tuples
     *         read and the #of solutions produced!
     */
    static private long add(final long cumulativeEstimatedCardinality,
            final EdgeSample edgeSample) {

        final long total = cumulativeEstimatedCardinality + //
                edgeSample.estimatedCardinality //
//                + edgeSample.tuplesRead //
        ;

        return total;
        
    }

    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Path{[");
        boolean first = true;
        for (Vertex v : vertices) {
            if (!first)
                sb.append(",");
            sb.append(v.pred.getId());
            first = false;
        }
//        for (Edge e : edges) {
//            if (!first)
//                sb.append(",");
//            sb.append(e.getLabel());
//            first = false;
//        }
        sb.append("],cumEstCard=" + cumulativeEstimatedCardinality
                + ",sample=" + edgeSample + "}");
        return sb.toString();
    }

//    /**
//     * Create an empty path.
//     */
//    public Path() {
////        this.edges = Collections.emptyList();
//        this.vertices = new Vertex[0];
//        this.preds = new IPredicate[0];
//        this.cumulativeEstimatedCardinality = 0;
//        this.sample = null;
//    }

    /**
     * Create a path from a single edge.
     * 
     * @param v0
     *            The initial vertex in the path.
     * @param v1
     *            The 2nd vertex in the path.
     * @param edgeSample
     *            The sample obtained from the cutoff join of (v0,v1).
     */
    public Path(final Vertex v0, final Vertex v1, final EdgeSample edgeSample) {

        if (v0 == null)
            throw new IllegalArgumentException();

        if (v1 == null)
            throw new IllegalArgumentException();

        if (v0.sample == null)
            throw new IllegalArgumentException();

        if (edgeSample == null)
            throw new IllegalArgumentException();

        if (edgeSample.sample == null)
            throw new IllegalArgumentException();

//        this.edges = Collections.singletonList(e);

        this.vertices = new Vertex[]{v0,v1};//getVertices(edges);

        this.preds = getPredicates(vertices);
        
        this.edgeSample = edgeSample;

        this.cumulativeEstimatedCardinality = add(0L/*cumulativeEstimatedCardinality*/,edgeSample);
//            edgeSample.estimatedCardinality +//
//            edgeSample.tuplesRead// this is part of the cost too.
//            ;

//        this.cumulativeEstimatedCardinality = //
//            edgeSample.estimatedCardinality +//
//            edgeSample.tuplesRead// this is part of the cost too.
//            ;

    }

    /**
     * Private constructor used when we extend a path.
     * 
     * @param vertices
     *            The ordered array of vertices in the new path. The last entry
     *            in this array is the vertex which was used to extend the path.
     * @param preds
     *            The ordered array of predicates in the new path (correlated
     *            with the vertices and passed in since it is already computed
     *            by the caller).
     * @param cumulativeEstimatedCardinality
     *            The cumulative estimated cardinality of the new path.
     * @param edgeSample
     *            The sample from the cutoff join of the last vertex added to
     *            this path.
     */
    private Path(//
            final Vertex[] vertices,//
            final IPredicate<?>[] preds,//
//            final List<Edge> edges,//
            final long cumulativeEstimatedCardinality,//
            final EdgeSample edgeSample//
            ) {

        if (vertices == null)
            throw new IllegalArgumentException();

        if (preds == null)
            throw new IllegalArgumentException();

        if (vertices.length != preds.length)
            throw new IllegalArgumentException();

        if (cumulativeEstimatedCardinality < 0)
            throw new IllegalArgumentException();

        if (edgeSample == null)
            throw new IllegalArgumentException();

        if (edgeSample.sample == null)
            throw new IllegalArgumentException();

//        this.edges = Collections.unmodifiableList(edges);

        this.vertices = vertices;
        
        this.preds = preds;
        
        this.cumulativeEstimatedCardinality = cumulativeEstimatedCardinality;

        this.edgeSample = edgeSample;
        
    }

    /**
     * Return the #of vertices in this join path.
     */
    public int getVertexCount() {
        
        return vertices.length;
        
    }

    /**
     * Return <code>true</code> iff the {@link Path} contains that
     * {@link Vertex}.
     * 
     * @param v
     *            The vertex
     * 
     * @return true if the vertex is already part of the path.
     */
    public boolean contains(final Vertex v) {

        if (v == null)
            throw new IllegalArgumentException();

        for (Vertex x : vertices) {
         
            if (v == x)
                return true;
            
        }
//        for (Edge e : edges) {
//
//            if (e.v1 == v || e.v2 == v)
//                return true;
//
//        }

        return false;
    }

    /**
     * Return <code>true</code> if this path is an unordered variant of the
     * given path (same vertices in any order).
     * 
     * @param p
     *            Another path.
     * 
     * @return <code>true</code> if this path is an unordered variant of the
     *         given path.
     */
    public boolean isUnorderedVariant(final Path p) {

        if (p == null)
            throw new IllegalArgumentException();

        if (vertices.length != p.vertices.length) {
            /*
             * Fast rejection. This assumes that each edge after the first
             * adds one distinct vertex to the path. That assumption is
             * enforced by #addEdge().
             */
            return false;
        }

        final Vertex[] v1 = this.vertices;
        final Vertex[] v2 = p.vertices;

        if (v1.length != v2.length) {

            // Reject (this case is also covered by the test above).
            return false;
            
        }

        /*
         * Scan the vertices of the caller's path. If any of those vertices
         * are NOT found in this path the paths are not unordered variations
         * of one another.
         */
        for (int i = 0; i < v2.length; i++) {

            final Vertex tmp = v2[i];

            boolean found = false;
            for (int j = 0; j < v1.length; j++) {

                if (v1[j] == tmp) {
                    found = true;
                    break;
                }

            }

            if (!found) {
                return false;
            }

        }

        return true;

    }

    /**
     * Return the vertices in this path (in path order). For the first edge,
     * the minimum cardinality vertex is always reported first (this is
     * critical for producing the correct join plan). For the remaining
     * edges in the path, the unvisited is reported.
     * 
     * @return The vertices (in path order).
     */
    public List<Vertex> getVertices() {

        return Collections.unmodifiableList(Arrays.asList(vertices));

    }

    /**
     * Return the {@link IPredicate}s associated with the vertices of the
     * join path in path order.
     * 
     * @see #getVertices()
     */
    public IPredicate<?>[] getPredicates() {

        return preds;

    }

    /**
     * Return the {@link BOp} identifiers of the predicates associated with
     * each vertex in path order.
     */
    public int[] getVertexIds() {
        
//        return getVertexIds(edges);
        
        return BOpUtility.getPredIds(preds);
        
    }
    
    /**
     * Return the predicates associated with the vertices.
     * 
     * @param vertices
     *            The vertices in the selected evaluation order.
     * 
     * @return The predicates associated with those vertices in the same order.
     */
    static private IPredicate<?>[] getPredicates(final Vertex[] vertices) {

        // The predicates in the same order as the vertices.
        final IPredicate<?>[] preds = new IPredicate[vertices.length];

        for (int i = 0; i < vertices.length; i++) {

            preds[i] = vertices[i].pred;

        }

        return preds;

    }

    /**
     * Return <code>true</code> if this path begins with the given path.
     * 
     * @param p
     *            The given path.
     * 
     * @return <code>true</code> if this path begins with the given path.
     * 
     * @todo unit tests.
     */
    public boolean beginsWith(final Path p) {

        if (p == null)
            throw new IllegalArgumentException();

        if (vertices.length > p.vertices.length) {
            // Proven false since the caller's path is longer.
            return false;
        }

        for (int i = 0; i < p.vertices.length; i++) {

            final Vertex vSelf = vertices[i];
            
            final Vertex vOther = p.vertices[i];
            
            if (vSelf != vOther) {
            
                return false;
                
            }
            
        }

        return true;
    }

    /**
     * Return the first N {@link IPredicate}s in this {@link Path}.
     * 
     * @param length
     *            The length of the path segment.
     * 
     * @return The path segment.
     */
    public IPredicate<?>[] getPathSegment(final int length) {

        if (length > preds.length)
            throw new IllegalArgumentException();

        final IPredicate<?>[] preds2 = new IPredicate[length];
        
        System.arraycopy(preds/* src */, 0/* srcPos */, preds2/* dest */,
                0/* destPos */, length);
        
        return preds2;

    }

    /**
     * Add an edge to a path, computing the estimated cardinality of the new
     * path, and returning the new path. The cutoff join is performed using the
     * {@link #edgeSample} of <i>this</i> join path and the actual access path
     * for the target vertex.
     * 
     * @param queryEngine
     * @param limit
     * @param vnew
     *            The new vertex.
     * @param constraints
     *            The join graph constraints (if any).
     * 
     * @return The new path. The materialized sample for the new path is the
     *         sample obtained by the cutoff join for the edge added to the
     *         path.
     * 
     * @throws Exception
     */
    public Path addEdge(final QueryEngine queryEngine, final int limit,
            final Vertex vnew, final IConstraint[] constraints)
            throws Exception {

        if (vnew == null)
            throw new IllegalArgumentException();

        if(contains(vnew))
            throw new IllegalArgumentException(
                "Vertex already present in path: vnew=" + vnew + ", path="
                        + this);

        if (this.edgeSample == null)
            throw new IllegalStateException();

        // The new vertex.
        final Vertex targetVertex = vnew;

        /*
         * Chain sample the edge.
         * 
         * Note: ROX uses the intermediate result I(p) for the existing path as
         * the input when sampling the edge. The corresponding concept for us is
         * the sample for this Path, which will have all variable bindings
         * produced so far. In order to estimate the cardinality of the new join
         * path we have to do a one step cutoff evaluation of the new Edge,
         * given the sample available on the current Path.
         * 
         * FIXME It is possible for the resulting edge sample to be empty (no
         * solutions). Unless the sample also happens to be exact, this is an
         * indication that the estimated cardinality has underflowed. We track
         * the estimated cumulative cardinality, so this does not make the join
         * path an immediate winner, but it does mean that we can not probe
         * further on that join path as we lack any intermediate solutions to
         * feed into the downstream joins. To resolve that, we have to increase
         * the sample limit (unless the path is the winner, in which case we can
         * fully execute the join path segment and materialize the results and
         * use those to probe further, but this will require the use of the
         * memory manager to keep the materialized intermediate results off of
         * the Java heap).
         */

        // Ordered array of all predicates including the target vertex.
        final IPredicate<?>[] preds2;
        final Vertex[] vertices2;
        {
            preds2 = new IPredicate[preds.length + 1];

            vertices2 = new Vertex[preds.length + 1];

            System.arraycopy(preds/* src */, 0/* srcPos */, preds2/* dest */,
                    0/* destPos */, preds.length);
            
            System.arraycopy(vertices/* src */, 0/* srcPos */, vertices2/* dest */,
                    0/* destPos */, preds.length);

            preds2[preds.length] = targetVertex.pred;
            
            vertices2[preds.length] = targetVertex;
            
        }

        final EdgeSample edgeSample2 = cutoffJoin(//
                queryEngine,//
                limit, //
                preds2,//
                constraints,//
                this.edgeSample // the source sample.
                );

        {

            final long cumulativeEstimatedCardinality = add(
                    this.cumulativeEstimatedCardinality, edgeSample2);

            // Extend the path.
            final Path tmp = new Path(vertices2, preds2,
                    cumulativeEstimatedCardinality, edgeSample2);

            return tmp;

        }

    }

    /**
     * Cutoff join of the last vertex in the join path.
     * <p>
     * <strong>The caller is responsible for protecting against needless
     * re-sampling.</strong> This includes cases where a sample already exists
     * at the desired sample limit and cases where the sample is already exact.
     * 
     * @param queryEngine
     *            The query engine.
     * @param limit
     *            The limit for the cutoff join.
     * @param path
     *            The path segment, which must include the target vertex as the
     *            last component of the path segment.
     * @param constraints
     *            The constraints declared for the join graph (if any). The
     *            appropriate constraints will be applied based on the variables
     *            which are known to be bound as of the cutoff join for the last
     *            vertex in the path segment.
     * @param sourceSample
     *            The input sample for the cutoff join. When this is a one-step
     *            estimation of the cardinality of the edge, then this sample is
     *            taken from the {@link VertexSample}. When the edge (vSource,
     *            vTarget) extends some {@link Path}, then this is taken from
     *            the {@link EdgeSample} for that {@link Path}.
     * 
     * @return The result of sampling that edge.
     * 
     * @throws Exception
     */
    static public EdgeSample cutoffJoin(//
            final QueryEngine queryEngine,//
            final int limit,//
            final IPredicate<?>[] path,//
            final IConstraint[] constraints,//
            final SampleBase sourceSample//
    ) throws Exception {

        if (path == null)
            throw new IllegalArgumentException();

        if (limit <= 0)
            throw new IllegalArgumentException();

        // The access path on which the cutoff join will read.
        final IPredicate<?> pred = path[path.length - 1];

        if (pred == null)
            throw new IllegalArgumentException();

        if (sourceSample == null)
            throw new IllegalArgumentException();
        
        if (sourceSample.sample == null)
            throw new IllegalArgumentException();
        
        // Figure out which constraints attach to each predicate.
        final IConstraint[][] constraintAttachmentArray = PartitionedJoinGroup
                .getJoinGraphConstraints(path, constraints);

        // The constraint(s) (if any) for this join.
        final IConstraint[] c = constraintAttachmentArray[path.length - 1];
        
        /*
         * Setup factory for bopIds with reservations for ones already in use.
         */
        final BOpIdFactory idFactory = new BOpIdFactory();

        // Reservation for the bopId used by the predicate.
        idFactory.reserve(pred.getId());
        
        // Reservations for the bopIds used by the constraints.
        if (c != null) {
            for (IConstraint x : c) {
                if (log.isTraceEnabled())
                    log.trace(Arrays.toString(BOpUtility.getPredIds(path))
                            + ": constraint: " + x);
                final Iterator<BOp> itr = BOpUtility
                        .preOrderIteratorWithAnnotations(x);
                while (itr.hasNext()) {
                    final BOp y = itr.next();
                    final Integer anId = (Integer) y
                            .getProperty(BOp.Annotations.BOP_ID);
                    if (anId != null)
                        idFactory.reserve(anId.intValue());
                }
            }
        }

        /*
         * Set up a cutoff pipeline join operator which makes an accurate
         * estimate of the #of input solutions consumed and the #of output
         * solutions generated. From that, we can directly compute the join hit
         * ratio.
         * 
         * Note: This approach is preferred to injecting a "RowId" column as the
         * estimates are taken based on internal counters in the join operator
         * and the join operator knows how to cutoff evaluation as soon as the
         * limit is satisfied, thus avoiding unnecessary effort.
         */

        final int joinId = idFactory.nextId();
        final Map<String, Object> anns = NV.asMap(//
            new NV(BOp.Annotations.BOP_ID, joinId),//
            new NV(PipelineJoin.Annotations.PREDICATE, pred),//
            // Note: does not matter since not executed by the query
            // controller.
            // // disallow parallel evaluation of tasks
            // new NV(PipelineOp.Annotations.MAX_PARALLEL,1),
            // disallow parallel evaluation of chunks.
            new NV(PipelineJoin.Annotations.MAX_PARALLEL_CHUNKS, 0),
            // disable access path coalescing
            new NV( PipelineJoin.Annotations.COALESCE_DUPLICATE_ACCESS_PATHS, false), //
            // pass in constraints on this join.
            new NV(PipelineJoin.Annotations.CONSTRAINTS, c),//
            // cutoff join.
            new NV(PipelineJoin.Annotations.LIMIT, (long) limit),
            /*
             * Note: In order to have an accurate estimate of the
             * join hit ratio we need to make sure that the join
             * operator runs using a single PipelineJoinStats
             * instance which will be visible to us when the query
             * is cutoff. In turn, this implies that the join must
             * be evaluated on the query controller.
             * 
             * @todo This implies that sampling of scale-out joins
             * must be done using remote access paths.
             */
            new NV(PipelineJoin.Annotations.SHARED_STATE, true),//
            new NV(PipelineJoin.Annotations.EVALUATION_CONTEXT,
                    BOpEvaluationContext.CONTROLLER)//
            );

        @SuppressWarnings("unchecked")
        final PipelineJoin<?> joinOp = new PipelineJoin(new BOp[] {}, anns);

        final PipelineOp queryOp = joinOp;

        // run the cutoff sampling of the edge.
        final UUID queryId = UUID.randomUUID();
        final IRunningQuery runningQuery = queryEngine.eval(queryId, queryOp,
                new LocalChunkMessage<IBindingSet>(queryEngine, queryId, joinOp
                        .getId()/* startId */, -1 /* partitionId */,
                        new ThickAsynchronousIterator<IBindingSet[]>(
                                new IBindingSet[][] { sourceSample.sample })));

        final List<IBindingSet> result = new LinkedList<IBindingSet>();
        try {
            try {
                IBindingSet bset = null;
                // Figure out the #of source samples consumed.
                final Iterator<IBindingSet> itr = new Dechunkerator<IBindingSet>(
                        runningQuery.iterator());
                while (itr.hasNext()) {
                    bset = itr.next();
                    result.add(bset);
                }
            } finally {
                // verify no problems.
                runningQuery.get();
            }
        } finally {
            runningQuery.cancel(true/* mayInterruptIfRunning */);
        }

        // The join hit ratio can be computed directly from these stats.
        final PipelineJoinStats joinStats = (PipelineJoinStats) runningQuery
                .getStats().get(joinId);

        if (log.isTraceEnabled())
            log.trace(Arrays.toString(BOpUtility.getPredIds(path)) + ": "
                    + joinStats.toString());

        // #of solutions in.
        final int inputCount = (int) joinStats.inputSolutions.get();

        // #of solutions out.
        long outputCount = joinStats.outputSolutions.get();

        // cumulative range count of the sampled access paths.
        final long sumRangeCount = joinStats.accessPathRangeCount.get();

        final EstimateEnum estimateEnum;
        if (sourceSample.estimateEnum == EstimateEnum.Exact
                && outputCount < limit) {
            /*
             * Note: If the entire source vertex is being fed into the cutoff
             * join and the cutoff join outputCount is LT the limit, then the
             * sample is the actual result of the join. That is, feeding all
             * source solutions into the join gives fewer than the desired
             * number of output solutions.
             */
            estimateEnum = EstimateEnum.Exact;
        } else if (inputCount == 1 && outputCount == limit) {
            /*
             * If the inputCount is ONE (1) and the outputCount is the limit,
             * then the estimated cardinality is a lower bound as more than
             * outputCount solutions might be produced by the join when
             * presented with a single input solution.
             * 
             * However, this condition suggests that the sum of the sampled
             * range counts is a much better estimate of the cardinality of this
             * join.
             * 
             * For example, consider a join feeding a rangeCount of 16 into a
             * rangeCount of 175000. With a limit of 100, we estimated the
             * cardinality at 1600L (lower bound). In fact, the cardinality is
             * 16*175000. This falsely low estimate can cause solutions which
             * are really better to be dropped.
             */
            // replace outputCount with the sum of the sampled range counts.
            outputCount = sumRangeCount;
            estimateEnum = EstimateEnum.LowerBound;
        } else if ((sourceSample.estimateEnum != EstimateEnum.Exact)
                && inputCount == Math.min(sourceSample.limit,
                        sourceSample.estimatedCardinality) && outputCount == 0) {
            /*
             * When the source sample was not exact, the inputCount is EQ to the
             * lesser of the source range count and the source sample limit, and
             * the outputCount is ZERO (0), then feeding in all source solutions
             * is not sufficient to generate any output solutions. In this case,
             * the estimated join hit ratio appears to be zero. However, the
             * estimation of the join hit ratio actually underflowed and the
             * real join hit ratio might be a small non-negative value. A real
             * zero can only be identified by executing the full join.
             * 
             * Note: An apparent join hit ratio of zero does NOT imply that the
             * join will be empty (unless the source vertex sample is actually
             * the fully materialized access path - this case is covered above).
             */
            estimateEnum = EstimateEnum.Underflow;
        } else {
            estimateEnum = EstimateEnum.Normal;
        }

        /*
         * The #of tuples read from the sampled access paths. This is part of
         * the cost of the join path, even though it is not part of the expected
         * cardinality of the cutoff join.
         * 
         * Note: While IOs is a better predictor of latency, it is possible to
         * choose a pipelined join versus a hash join once the query plan has
         * been decided. Their IO provides are both correlated to the #of tuples
         * read.
         */
        final long tuplesRead = joinStats.accessPathUnitsIn.get();
        
        final double f = outputCount == 0 ? 0
                : (outputCount / (double) inputCount);

        final long estimatedCardinality = (long) (sourceSample.estimatedCardinality * f);

        final EdgeSample edgeSample = new EdgeSample(//
                sourceSample,//
                inputCount,//
                outputCount, //
                tuplesRead,//
                f, //
                // args to SampleBase
                estimatedCardinality, //
                limit, //
                estimateEnum,//
                result.toArray(new IBindingSet[result.size()]));

        if (log.isDebugEnabled())
            log.debug(Arrays.toString(BOpUtility.getPredIds(path))
                    + ": newSample=" + edgeSample);

        return edgeSample;

    }

}
