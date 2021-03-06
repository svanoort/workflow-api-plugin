/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.graphanalysis;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.cps.steps.ParallelStep;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.junit.Assert;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Slightly dirty but it removes a ton of FlowTestUtils.* class qualifiers
import static org.jenkinsci.plugins.workflow.graphanalysis.FlowTestUtils.*;

/**
 * Tests for internals of ForkScanner
 */
public class ForkScannerTest {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public JenkinsRule r = new JenkinsRule();

    public static Predicate<TestVisitor.CallEntry> predicateForCallEntryType(final TestVisitor.CallType type) {
        return new Predicate<TestVisitor.CallEntry>() {
            TestVisitor.CallType myType = type;
            @Override
            public boolean apply(TestVisitor.CallEntry input) {
                return input.type != null && input.type == myType;
            }
        };
    }

    /** Flow structure (ID - type)
     2 - FlowStartNode (BlockStartNode)
     3 - Echostep
     4 - ParallelStep (StepStartNode) (start branches)
     6 - ParallelStep (StepStartNode) (start branch 1), ParallelLabelAction with branchname=1
     7 - ParallelStep (StepStartNode) (start branch 2), ParallelLabelAction with branchname=2
     8 - EchoStep, (branch 1) parent=6
     9 - StepEndNode, (end branch 1) startId=6, parentId=8
     10 - EchoStep, (branch 2) parentId=7
     11 - EchoStep, (branch 2) parentId = 10
     12 - StepEndNode (end branch 2)  startId=7  parentId=11,
     13 - StepEndNode (close branches), parentIds = 9,12, startId=4
     14 - EchoStep
     15 - FlowEndNode (BlockEndNode)
     */
    WorkflowRun SIMPLE_PARALLEL_RUN;

    /** Parallel nested in parallel (ID-type)
     * 2 - FlowStartNode (BlockStartNode)
     * 3 - Echostep
     * 4 - ParallelStep (stepstartnode)
     * 6 - ParallelStep (StepStartNode) (start branch 1), ParallelLabelAction with branchname=1
     * 7 - ParallelStep (StepStartNode) (start branch 2), ParallelLabelAction with branchname=2
     * 8 - EchoStep (branch #1) - parentId=6
     * 9 - StepEndNode (end branch #1) - startId=6
     * 10 - EchoStep - parentId=7
     * 11 - EchoStep
     * 12 - ParallelStep (StepStartNode) - start inner parallel
     * 14 - ParallelStep (StepStartNode) (start branch 2-1), parentId=12, ParallelLabellAction with branchName=2-1
     * 15 - ParallelStep (StepStartNode) (start branch 2-2), parentId=12, ParallelLabelAction with branchName=2-2
     * 16 - Echo (Branch2-1), parentId=14
     * 17 - StepEndNode (end branch 2-1), parentId=16, startId=14
     * 18 - SleepStep (branch 2-2) parentId=15
     * 19 - EchoStep (branch 2-2)
     * 20 - StepEndNode (end branch 2-2), startId=15
     * 21 - StepEndNode (end inner parallel ), parentIds=17,20, startId=12
     * 22 - StepEndNode (end parallel #2), parent=21, startId=7
     * 23 - StepEndNode (end outer parallel), parentIds=9,22, startId=4
     * 24 - Echo
     * 25 - FlowEndNode
     */
    WorkflowRun NESTED_PARALLEL_RUN;

    @Before
    public void setUp() throws Exception {
        r.jenkins.getInjector().injectMembers(this);

        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "SimpleParallel");
        job.setDefinition(new CpsFlowDefinition(
                "echo 'first'\n" +
                        "def steps = [:]\n" +
                        "steps['1'] = {\n" +
                        "    echo 'do 1 stuff'\n" +
                        "}\n" +
                        "steps['2'] = {\n" +
                        "    echo '2a'\n" +
                        "    echo '2b'\n" +
                        "}\n" +
                        "parallel steps\n" +
                        "echo 'final'"
        ));
        WorkflowRun b = r.assertBuildStatusSuccess(job.scheduleBuild2(0));
        this.SIMPLE_PARALLEL_RUN = b;

        job = r.jenkins.createProject(WorkflowJob.class, "NestedParallel");
        job.setDefinition(new CpsFlowDefinition(
                "echo 'first'\n" +
                        "def steps = [:]\n" +
                        "steps['1'] = {\n" +
                        "    echo 'do 1 stuff'\n" +
                        "}\n" +
                        "steps['2'] = {\n" +
                        "    echo '2a'\n" +
                        "    echo '2b'\n" +
                        "    def nested = [:]\n" +
                        "    nested['2-1'] = {\n" +
                        "        echo 'do 2-1'\n" +
                        "    } \n" +
                        "    nested['2-2'] = {\n" +
                        "        sleep 1\n" +
                        "        echo '2 section 2'\n" +
                        "    }\n" +
                        "    parallel nested\n" +
                        "}\n" +
                        "parallel steps\n" +
                        "echo 'final'"
        ));
        b = r.assertBuildStatusSuccess(job.scheduleBuild2(0));
        this.NESTED_PARALLEL_RUN = b;
    }

    public static Predicate<FlowNode> PARALLEL_START_PREDICATE = new Predicate<FlowNode>() {
        @Override
        public boolean apply(FlowNode input) {
            return input != null && input instanceof StepStartNode && (((StepStartNode) input).getDescriptor().getClass() == ParallelStep.DescriptorImpl.class);
        }
    };

    @Test
    public void testForkedScanner() throws Exception {
        FlowExecution exec = SIMPLE_PARALLEL_RUN.getExecution();
        Collection<FlowNode> heads =  SIMPLE_PARALLEL_RUN.getExecution().getCurrentHeads();

        // Initial case
        ForkScanner scanner = new ForkScanner();
        scanner.setup(heads, null);
        ForkScanner.setParallelStartPredicate(PARALLEL_START_PREDICATE);
        Assert.assertNull(scanner.currentParallelStart);
        Assert.assertNull(scanner.currentParallelStartNode);
        Assert.assertNotNull(scanner.parallelBlockStartStack);
        Assert.assertEquals(0, scanner.parallelBlockStartStack.size());
        Assert.assertTrue(scanner.isWalkingFromFinish());

        // Fork case
        scanner.setup(exec.getNode("13"));
        Assert.assertFalse(scanner.isWalkingFromFinish());
        Assert.assertEquals(null, scanner.currentType);
        Assert.assertEquals(ForkScanner.NodeType.PARALLEL_END, scanner.nextType);
        Assert.assertEquals("13", scanner.next().getId());
        Assert.assertNotNull(scanner.parallelBlockStartStack);
        Assert.assertEquals(0, scanner.parallelBlockStartStack.size());
        Assert.assertEquals(exec.getNode("4"), scanner.currentParallelStartNode);

        ForkScanner.ParallelBlockStart start = scanner.currentParallelStart;
        Assert.assertEquals(1, start.unvisited.size());
        Assert.assertEquals(exec.getNode("4"), start.forkStart);

        /** Flow structure (ID - type)
         2 - FlowStartNode (BlockStartNode)
         3 - Echostep
         4 - ParallelStep (StepStartNode) (start branches)
         6 - ParallelStep (StepStartNode) (start branch 1), ParallelLabelAction with branchname=1
         7 - ParallelStep (StepStartNode) (start branch 2), ParallelLabelAction with branchname=2
         8 - EchoStep, (branch 1) parent=6
         9 - StepEndNode, (end branch 1) startId=6, parentId=8
         10 - EchoStep, (branch 2) parentId=7
         11 - EchoStep, (branch 2) parentId = 10
         12 - StepEndNode (end branch 2)  startId=7  parentId=11,
         13 - StepEndNode (close branches), parentIds = 9,12, startId=4
         14 - EchoStep
         15 - FlowEndNode (BlockEndNode)
         */

        Assert.assertEquals(exec.getNode("12"), scanner.next()); //12
        Assert.assertEquals(ForkScanner.NodeType.PARALLEL_BRANCH_END, scanner.getCurrentType());
        Assert.assertEquals(ForkScanner.NodeType.NORMAL, scanner.getNextType());
        Assert.assertEquals(exec.getNode("11"), scanner.next());
        Assert.assertEquals(ForkScanner.NodeType.NORMAL, scanner.getCurrentType());
        Assert.assertEquals(exec.getNode("10"), scanner.next());
        Assert.assertEquals(ForkScanner.NodeType.NORMAL, scanner.getCurrentType());
        Assert.assertEquals(ForkScanner.NodeType.PARALLEL_BRANCH_START, scanner.getNextType());
        Assert.assertEquals(exec.getNode("7"), scanner.next());
        Assert.assertEquals(ForkScanner.NodeType.PARALLEL_BRANCH_START, scanner.getCurrentType());

        // Next branch, branch 1 (since we visit in reverse)
        Assert.assertEquals(ForkScanner.NodeType.PARALLEL_BRANCH_END, scanner.getNextType());
        Assert.assertEquals(exec.getNode("9"), scanner.next());
        Assert.assertEquals(ForkScanner.NodeType.PARALLEL_BRANCH_END, scanner.getCurrentType());
        Assert.assertEquals(exec.getNode("8"), scanner.next());
        Assert.assertEquals(ForkScanner.NodeType.NORMAL, scanner.getCurrentType());
        Assert.assertEquals(exec.getNode("6"), scanner.next());
        Assert.assertEquals(ForkScanner.NodeType.PARALLEL_BRANCH_START, scanner.getCurrentType());
        Assert.assertEquals(ForkScanner.NodeType.PARALLEL_START, scanner.getNextType());
    }

    /** Reference the flow graphs in {@link #SIMPLE_PARALLEL_RUN} and {@link #NESTED_PARALLEL_RUN} */
    @Test
    public void testFlowSegmentSplit() throws Exception {
        FlowExecution exec = SIMPLE_PARALLEL_RUN.getExecution();

        /** Flow structure (ID - type)
         2 - FlowStartNode (BlockStartNode)
         3 - Echostep
         4 - ParallelStep (StepStartNode) (start branches)
         6 - ParallelStep (StepStartNode) (start branch 1), ParallelLabelAction with branchname=1
         7 - ParallelStep (StepStartNode) (start branch 2), ParallelLabelAction with branchname=2
         8 - EchoStep, (branch 1) parent=6
         9 - StepEndNode, (end branch 1) startId=6, parentId=8
         10 - EchoStep, (branch 2) parentId=7
         11 - EchoStep, (branch 2) parentId = 10
         12 - StepEndNode (end branch 2)  startId=7  parentId=11,
         13 - StepEndNode (close branches), parentIds = 9,12, startId=4
         14 - EchoStep
         15 - FlowEndNode (BlockEndNode)
         */

        HashMap<FlowNode, ForkScanner.FlowPiece> nodeMap = new HashMap<FlowNode,ForkScanner.FlowPiece>();
        ForkScanner.FlowSegment mainBranch = new ForkScanner.FlowSegment();
        ForkScanner.FlowSegment sideBranch = new ForkScanner.FlowSegment();
        FlowNode BRANCH1_END = exec.getNode("9");
        FlowNode BRANCH2_END = exec.getNode("12");
        FlowNode START_PARALLEL = exec.getNode("4");

        // Branch 1, we're going to run one flownode beyond the start of the parallel branch and then split
        mainBranch.add(BRANCH1_END);
        mainBranch.add(exec.getNode("8"));
        mainBranch.add(exec.getNode("6"));
        mainBranch.add(exec.getNode("4"));
        mainBranch.add(exec.getNode("3"));  // FlowNode beyond the fork point
        for (FlowNode f : mainBranch.visited) {
            nodeMap.put(f, mainBranch);
        }
        assertNodeOrder("Visited nodes", mainBranch.visited, 9, 8, 6, 4, 3);

        // Branch 2
        sideBranch.add(BRANCH2_END);
        sideBranch.add(exec.getNode("11"));
        sideBranch.add(exec.getNode("10"));
        sideBranch.add(exec.getNode("7"));
        for (FlowNode f : sideBranch.visited) {
            nodeMap.put(f, sideBranch);
        }
        assertNodeOrder("Visited nodes", sideBranch.visited, 12, 11, 10, 7);

        ForkScanner.Fork forked = mainBranch.split(nodeMap, (BlockStartNode)exec.getNode("4"), sideBranch);
        ForkScanner.FlowSegment splitSegment = (ForkScanner.FlowSegment)nodeMap.get(BRANCH1_END); // New branch
        Assert.assertNull(splitSegment.after);
        assertNodeOrder("Branch 1 split after fork", splitSegment.visited, 9, 8, 6);

        // Just the single node before the fork
        Assert.assertEquals(forked, mainBranch.after);
        assertNodeOrder("Head of flow, pre-fork", mainBranch.visited, 3);

        // Fork point
        Assert.assertEquals(forked, nodeMap.get(START_PARALLEL));
        ForkScanner.FlowPiece[] follows = {splitSegment, sideBranch};
        Assert.assertArrayEquals(follows, forked.following.toArray());

        // Branch 2
        Assert.assertEquals(sideBranch, nodeMap.get(BRANCH2_END));
        assertNodeOrder("Branch 2", sideBranch.visited, 12, 11, 10, 7);

        // Test me where splitting right at a fork point, where we should have a fork with and main branch shoudl become following
        // Along with side branch (branch2)
        nodeMap.clear();
        mainBranch = new ForkScanner.FlowSegment();
        sideBranch = new ForkScanner.FlowSegment();
        mainBranch.visited.add(exec.getNode("6"));
        mainBranch.visited.add(START_PARALLEL);
        sideBranch.visited.add(exec.getNode("7"));
        for (FlowNode f : mainBranch.visited) {
            nodeMap.put(f, mainBranch);
        }
        nodeMap.put(exec.getNode("7"), sideBranch);

        forked = mainBranch.split(nodeMap, (BlockStartNode)exec.getNode("4"), sideBranch);
        follows = new ForkScanner.FlowSegment[2];
        follows[0] = mainBranch;
        follows[1] = sideBranch;
        Assert.assertArrayEquals(follows, forked.following.toArray());
        assertNodeOrder("Branch1", mainBranch.visited, 6);
        Assert.assertNull(mainBranch.after);
        assertNodeOrder("Branch2", sideBranch.visited, 7);
        Assert.assertNull(sideBranch.after);
        Assert.assertEquals(forked, nodeMap.get(START_PARALLEL));
        Assert.assertEquals(mainBranch, nodeMap.get(exec.getNode("6")));
        Assert.assertEquals(sideBranch, nodeMap.get(exec.getNode("7")));
    }

    @Test
    public void testEmptyParallel() throws Exception {
        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "EmptyParallel");
        job.setDefinition(new CpsFlowDefinition(
                "parallel 'empty1': {}, 'empty2':{} \n" +
                        "echo 'done' "
        ));
        WorkflowRun b = r.assertBuildStatusSuccess(job.scheduleBuild2(0));
        ForkScanner scan = new ForkScanner();

        List<FlowNode> outputs = scan.filteredNodes(b.getExecution().getCurrentHeads(), (Predicate) Predicates.alwaysTrue());
        Assert.assertEquals(9, outputs.size());
    }

    /** Reference the flow graphs in {@link #SIMPLE_PARALLEL_RUN} and {@link #NESTED_PARALLEL_RUN} */
    @Test
    public void testLeastCommonAncestor() throws Exception {
        FlowExecution exec = SIMPLE_PARALLEL_RUN.getExecution();

        ForkScanner scan = new ForkScanner();
        // Starts at the ends of the parallel branches
        Set<FlowNode> heads = new LinkedHashSet<FlowNode>(Arrays.asList(exec.getNode("12"), exec.getNode("9")));
        ArrayDeque<ForkScanner.ParallelBlockStart> starts = scan.leastCommonAncestor(heads);
        Assert.assertEquals(1, starts.size());

        ForkScanner.ParallelBlockStart start = starts.peek();
        Assert.assertEquals(2, start.unvisited.size());
        Assert.assertEquals(exec.getNode("4"), start.forkStart);
        Assert.assertArrayEquals(heads.toArray(), start.unvisited.toArray());

        // Ensure no issues with single start triggering least common ancestor
        heads = new LinkedHashSet<FlowNode>(Arrays.asList(exec.getNode("4")));
        scan.setup(heads);
        Assert.assertNull(scan.currentParallelStart);
        Assert.assertTrue(scan.parallelBlockStartStack == null || scan.parallelBlockStartStack.isEmpty());

        // Empty fork
        heads = new LinkedHashSet<FlowNode>(Arrays.asList(exec.getNode("6"), exec.getNode("7")));
        starts = scan.leastCommonAncestor(heads);
        Assert.assertEquals(1, starts.size());
        ForkScanner.ParallelBlockStart pbs = starts.pop();
        Assert.assertEquals(exec.getNode("4"), pbs.forkStart);
        Assert.assertEquals(2, pbs.unvisited.size());
        Assert.assertTrue(pbs.unvisited.contains(exec.getNode("6")));
        Assert.assertTrue(pbs.unvisited.contains(exec.getNode("7")));

        /** Now we do the same with nested run */
        exec = NESTED_PARALLEL_RUN.getExecution();
        heads = new LinkedHashSet<FlowNode>(Arrays.asList(exec.getNode("9"), exec.getNode("17"), exec.getNode("20")));

        // Problem: we get a parallel start with the same flowsegment in the following for more than one parallel start
        starts = scan.leastCommonAncestor(heads);
        Assert.assertEquals(2, starts.size());
        ForkScanner.ParallelBlockStart inner = starts.getFirst();
        ForkScanner.ParallelBlockStart outer = starts.getLast();

        Assert.assertEquals(2, inner.unvisited.size());
        Assert.assertEquals(exec.getNode("12"), inner.forkStart);

        Assert.assertEquals(1, outer.unvisited.size());
        Assert.assertEquals(exec.getNode("9"), outer.unvisited.peek());
        Assert.assertEquals(exec.getNode("4"), outer.forkStart);

        heads = new LinkedHashSet<FlowNode>(Arrays.asList(exec.getNode("9"), exec.getNode("17"), exec.getNode("20")));
        starts = scan.leastCommonAncestor(heads);
        Assert.assertEquals(2, starts.size());
    }

    @Test
    @Issue("JENKINS-38089")
    public void testVariousParallelCombos() throws Exception {
        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "ParallelTimingBug");
        job.setDefinition(new CpsFlowDefinition(
            // Seemingly gratuitous sleep steps are because original issue required specific timing to reproduce
            // TODO test to see if we still need them to reproduce JENKINS-38089
            "stage 'test' \n" +
            "    parallel 'unit': {\n" +
            "          retry(1) {\n" +
            "            sleep 1;\n" +
            "            sleep 10; echo 'hello'; \n" +
            "          }\n" +
            "        }, 'otherunit': {\n" +
            "            retry(1) {\n" +
            "              sleep 1;\n" +
            "              sleep 5; \n" +
            "              echo 'goodbye'   \n" +
            "            }\n" +
            "        }"
        ));
        /*Node dump follows, format:
        [ID]{parent,ids}(millisSinceStartOfRun) flowNodeClassName stepDisplayName [st=startId if a block end node]
        Action format:
        - actionClassName actionDisplayName
        ------------------------------------------------------------------------------------------
        [2]{}FlowStartNode Start of Pipeline
        [3]{2}StepAtomNode test
        [4]{3}StepStartNode Execute in parallel : Start
        [6]{4}StepStartNode Branch: unit
        [7]{4}StepStartNode Branch: otherunit
            A [8]{6}StepStartNode Retry the body up to N times : Start
            A [9]{8}StepStartNode Retry the body up to N times : Body : Start
          B [10]{7}StepStartNode Retry the body up to N times : Start
          B [11]{10}StepStartNode Retry the body up to N times : Body : Start
            A [12]{9}StepAtomNode Sleep
          B [13]{11}StepAtomNode Sleep
            A [14]{12}StepAtomNode Sleep
          B [15]{13}StepAtomNode Sleep
          B [16]{15}StepAtomNode Print Message
          B [17]{16}StepEndNode Retry the body up to N times : Body : End  [st=11]
          B [18]{17}StepEndNode Retry the body up to N times : End  [st=10]
          B [19]{18}StepEndNode Execute in parallel : Body : End  [st=7]
            A [20]{14}StepAtomNode Print Message
            A [21]{20}StepEndNode Retry the body up to N times : Body : End  [st=9]
            A [22]{21}StepEndNode Retry the body up to N times : End  [st=8]
            A [23]{22}StepEndNode Execute in parallel : Body : End  [st=6]
        [24]{23,19}StepEndNode Execute in parallel : End  [st=4]
        [25]{24}FlowEndNode End of Pipeline  [st=2]*/
        WorkflowRun b = r.assertBuildStatusSuccess(job.scheduleBuild2(0));
        FlowExecution exec = b.getExecution();
        ForkScanner scan = new ForkScanner();

        // Test different start points in branch A & B, 20 and 19 were one error case.
        for (int i=0; i < 4; i++) {
            for (int j=0; j<5; j++) {
                int branchANodeId = i+20;
                int branchBNodeId = j+15;
                System.out.println("Starting test with nodes "+branchANodeId+","+branchBNodeId);
                ArrayList<FlowNode> starts = new ArrayList<FlowNode>();
                FlowTestUtils.addNodesById(starts, exec, branchANodeId, branchBNodeId);
                List<FlowNode> all = scan.filteredNodes(starts, Predicates.<FlowNode>alwaysTrue());
                Assert.assertEquals(new HashSet<FlowNode>(all).size(), all.size());
                scan.reset();
            }
        }
    }

    /** For nodes, see {@link #SIMPLE_PARALLEL_RUN} */
    @Test
    public void testSimpleVisitor() throws Exception {
        ForkScanner.setParallelStartPredicate(PARALLEL_START_PREDICATE);
        FlowExecution exec = this.SIMPLE_PARALLEL_RUN.getExecution();
        ForkScanner f = new ForkScanner();
        f.setup(exec.getCurrentHeads());
        TestVisitor visitor = new TestVisitor();

        f.visitSimpleChunks(visitor, new BlockChunkFinder());

        // 13 calls for chunk/atoms, 6 for parallels
        Assert.assertEquals(19, visitor.calls.size());

        // End has nothing after it, just last node (15)
        TestVisitor.CallEntry last = new TestVisitor.CallEntry(TestVisitor.CallType.CHUNK_END, 15, -1, -1, -1);
        last.assertEquals(visitor.calls.get(0));

        // Start has nothing before it, just the first node (2)
        TestVisitor.CallEntry first = new TestVisitor.CallEntry(TestVisitor.CallType.CHUNK_START, 2, -1, -1, -1);
        first.assertEquals(visitor.calls.get(18));

        int chunkStartCount = Iterables.size(Iterables.filter(visitor.calls, predicateForCallEntryType(TestVisitor.CallType.CHUNK_START)));
        int chunkEndCount = Iterables.size(Iterables.filter(visitor.calls, predicateForCallEntryType(TestVisitor.CallType.CHUNK_END)));
        Assert.assertEquals(4, chunkStartCount);
        Assert.assertEquals(4, chunkEndCount);

        // Verify the AtomNode calls are correct
        List < TestVisitor.CallEntry > atomNodeCalls = Lists.newArrayList(Iterables.filter(visitor.calls, predicateForCallEntryType(TestVisitor.CallType.ATOM_NODE)));
        Assert.assertEquals(5, atomNodeCalls.size());
        for (TestVisitor.CallEntry ce : atomNodeCalls) {
            int beforeId = ce.ids[0];
            int atomNodeId = ce.ids[1];
            int afterId = ce.ids[2];
            int alwaysEmpty = ce.ids[3];
            Assert.assertTrue(ce+" beforeNodeId <= 0: "+beforeId, beforeId > 0);
            Assert.assertTrue(ce + " atomNodeId <= 0: " + atomNodeId, atomNodeId > 0);
            Assert.assertTrue(ce+" afterNodeId <= 0: "+afterId, afterId > 0);
            Assert.assertEquals(-1, alwaysEmpty);
            Assert.assertTrue(ce + "AtomNodeId >= afterNodeId", atomNodeId < afterId);
            Assert.assertTrue(ce+ "beforeNodeId >= atomNodeId", beforeId < atomNodeId);
        }


        List<TestVisitor.CallEntry> parallelCalls = Lists.newArrayList(Iterables.filter(visitor.calls, new Predicate<TestVisitor.CallEntry>() {
            @Override
            public boolean apply(TestVisitor.CallEntry input) {
                return input.type != null
                        && input.type != TestVisitor.CallType.ATOM_NODE
                        && input.type != TestVisitor.CallType.CHUNK_START
                        && input.type != TestVisitor.CallType.CHUNK_END;
            }
        }));
        Assert.assertEquals(6, parallelCalls.size());
        // Start to end
        new TestVisitor.CallEntry(TestVisitor.CallType.PARALLEL_END, 4, 13).assertEquals(parallelCalls.get(0));

        //Tests for parallel handling
        // Start to end, in reverse order

        new TestVisitor.CallEntry(TestVisitor.CallType.PARALLEL_BRANCH_END, 4, 12).assertEquals(parallelCalls.get(1));
        new TestVisitor.CallEntry(TestVisitor.CallType.PARALLEL_BRANCH_START, 4, 7).assertEquals(parallelCalls.get(2));
        new TestVisitor.CallEntry(TestVisitor.CallType.PARALLEL_BRANCH_END, 4, 9).assertEquals(parallelCalls.get(3));

        new TestVisitor.CallEntry(TestVisitor.CallType.PARALLEL_BRANCH_START, 4, 6).assertEquals(parallelCalls.get(4));
        new TestVisitor.CallEntry(TestVisitor.CallType.PARALLEL_START, 4, 6).assertEquals(parallelCalls.get(5));

    }

    /** Checks for off-by one cases with multiple parallel, and with the leastCommonAncestor */
    @Test
    public void testTripleParallel() throws Exception {
        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "TripleParallel");
        job.setDefinition(new CpsFlowDefinition(
                "stage 'test'\n"+   // Id 3, Id 2 before that has the FlowStartNode
                "parallel 'unit':{\n" + // Id 4 starts parallel, Id 7 is the block start for the unit branch
                "  echo \"Unit testing...\"\n" + // Id 10
                "},'integration':{\n" + // Id 11 is unit branch end, Id 8 is the branch start for integration branch
                "    echo \"Integration testing...\"\n" + // Id 12
                "}, 'ui':{\n" +  // Id 13 in integration branch end, Id 9 is branch start for UI branch
                "    echo \"UI testing...\"\n" + // Id 14
                "}" // Node 15 is UI branch end node, Node 16 is Parallel End node, Node 17 is FlowWendNode
        ));
        WorkflowRun b = r.assertBuildStatusSuccess(job.scheduleBuild2(0));

        ForkScanner.setParallelStartPredicate(PARALLEL_START_PREDICATE);
        FlowExecution exec = b.getExecution();
        ForkScanner f = new ForkScanner();
        f.setup(exec.getCurrentHeads());
        TestVisitor visitor = new TestVisitor();
        f.visitSimpleChunks(visitor, new BlockChunkFinder());

        ArrayList<TestVisitor.CallEntry> parallels = Lists.newArrayList(Iterables.filter(visitor.calls,
                Predicates.or(
                        predicateForCallEntryType(TestVisitor.CallType.PARALLEL_BRANCH_START),
                        predicateForCallEntryType(TestVisitor.CallType.PARALLEL_BRANCH_END))
                )
        );
        Assert.assertEquals(6, parallels.size());

        // Visiting from partially completed branches
        // Verify we still get appropriate parallels callbacks for a branch end
        //   even if in-progress and no explicit end node
        ArrayList<FlowNode> ends = new ArrayList<FlowNode>();
        ends.add(exec.getNode("11"));
        ends.add(exec.getNode("12"));
        ends.add(exec.getNode("14"));
        visitor = new TestVisitor();
        f.setup(ends);
        f.visitSimpleChunks(visitor, new BlockChunkFinder());
        parallels = Lists.newArrayList(Iterables.filter(visitor.calls,
                        Predicates.or(
                                predicateForCallEntryType(TestVisitor.CallType.PARALLEL_BRANCH_START),
                                predicateForCallEntryType(TestVisitor.CallType.PARALLEL_BRANCH_END))
                )
        );
        Assert.assertEquals(6, parallels.size());
        Assert.assertEquals(17, visitor.calls.size());

        // Test the least common ancestor implementation with triplicate
        FlowNode[] branchHeads = {exec.getNode("7"), exec.getNode("8"), exec.getNode("9")};
        ArrayDeque<ForkScanner.ParallelBlockStart> starts = f.leastCommonAncestor(new HashSet<FlowNode>(Arrays.asList(branchHeads)));
        Assert.assertEquals(1, starts.size());
        ForkScanner.ParallelBlockStart pbs = starts.pop();
        Assert.assertEquals(exec.getNode("4"), pbs.forkStart);
        Assert.assertEquals(3, pbs.unvisited.size());
        Assert.assertTrue(pbs.unvisited.contains(exec.getNode("7")));
        Assert.assertTrue(pbs.unvisited.contains(exec.getNode("8")));
        Assert.assertTrue(pbs.unvisited.contains(exec.getNode("9")));
    }

    private void testParallelFindsLast(WorkflowJob job, String semaphoreName) throws Exception {
        ForkScanner scan = new ForkScanner();
        ChunkFinder labelFinder = new LabelledChunkFinder();

        System.out.println("Testing that semaphore step is always the last step for chunk with "+job.getName());
        WorkflowRun run  = job.scheduleBuild2(0).getStartCondition().get();
        SemaphoreStep.waitForStart(semaphoreName+"/1", run);

            /*if (run.getExecution() == null) {
                Thread.sleep(1000);
            }*/

        TestVisitor visitor = new TestVisitor();
        scan.setup(run.getExecution().getCurrentHeads());
        scan.visitSimpleChunks(visitor, labelFinder);
        TestVisitor.CallEntry entry = visitor.calls.get(0);
        Assert.assertEquals(TestVisitor.CallType.CHUNK_END, entry.type);
        FlowNode lastNode = run.getExecution().getNode(Integer.toString(entry.ids[0]));
        Assert.assertEquals("Wrong End Node: ("+lastNode.getId()+") "+lastNode.getDisplayName(), "semaphore", lastNode.getDisplayFunctionName());

        SemaphoreStep.success(semaphoreName+"/1", null);
        r.waitForCompletion(run);
    }

    @Issue("JENKINS-38536")
    @Test
    public void testParallelCorrectEndNodeForVisitor() throws Exception {
        // Verify that SimpleBlockVisitor actually gets the *real* last node not just the last declared branch
        WorkflowJob jobPauseFirst = r.jenkins.createProject(WorkflowJob.class, "PauseFirst");
        jobPauseFirst.setDefinition(new CpsFlowDefinition("" +
                "stage 'primero'\n" +
                "parallel 'wait' : {sleep 1; semaphore 'wait1';}, \n" +
                " 'final': { echo 'succeed';} "
        ));

        WorkflowJob jobPauseSecond = r.jenkins.createProject(WorkflowJob.class, "PauseSecond");
        jobPauseSecond.setDefinition(new CpsFlowDefinition("" +
                "stage 'primero'\n" +
                "parallel 'success' : {echo 'succeed'}, \n" +
                " 'pause':{ sleep 1; semaphore 'wait2'; }\n"
                ));

        WorkflowJob jobPauseMiddle = r.jenkins.createProject(WorkflowJob.class, "PauseMiddle");
        jobPauseMiddle.setDefinition(new CpsFlowDefinition("" +
                "stage 'primero'\n" +
                "parallel 'success' : {echo 'succeed'}, \n" +
                " 'pause':{ sleep 1; semaphore 'wait3'; }, \n" +
                " 'final': { echo 'succeed-final';} "
        ));

        ForkScanner scan = new ForkScanner();
        ChunkFinder labelFinder = new LabelledChunkFinder();

        testParallelFindsLast(jobPauseFirst, "wait1");
        testParallelFindsLast(jobPauseSecond, "wait2");
        testParallelFindsLast(jobPauseMiddle, "wait3");
    }
}
