/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.setup.callgraph;

import org.evosuite.Properties;
import org.evosuite.classpath.ResourceList;
import org.evosuite.graphs.ddg.MethodEntry;
import org.evosuite.setup.Call;
import org.evosuite.setup.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.evosuite.setup.callgraph.CallGraph.MethodDepthPair.p;

/**
 * CallGraph implementation. Based on the previous implementation in the
 * CallTree class. This class is a wrapper of the Graph class. I didn't use the
 * jgrapht graph classes on purpose because I had problems with the DFS
 * algorithms implemented for them. On the bright hand, this implementation
 * should be more efficient.
 *
 * @author mattia
 *
 */

public class CallGraph implements Iterable<MethodEntry> {

	/**
	 * The CallGraphImpl class is a wrap of the Graph class. Internally the
	 * graph is represented reversed, i.e. if a method m1 points to a method m2,
	 * the graph connects the methods with an edge from m2 to m1. The methods in
	 * this class however mask this representation.
	 */
	private ReverseCallGraph graph = new ReverseCallGraph();

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory
			.getLogger(CallGraph.class);

	private final String className;

	private final Set<MethodEntry> cutNodes = Collections.synchronizedSet(new LinkedHashSet<>());

	private final Set<String> callGraphClasses = Collections.synchronizedSet(new LinkedHashSet<>());

	private final Set<String> toTestClasses = Collections.synchronizedSet(new LinkedHashSet<>());
	private final Set<String> toTestMethods = Collections.synchronizedSet(new LinkedHashSet<>());

	private final Set<String> notToTestClasses = Collections.synchronizedSet(new LinkedHashSet<>());


	private final Set<CallContext> publicMethods = Collections.synchronizedSet(new LinkedHashSet<>());

	// TODO: not sure if it really must be synchronized
	private final Map<MethodEntry, Set<MethodEntry>> callerCache = Collections.synchronizedMap(new LinkedHashMap<>());

	public CallGraph(String className) {
		this.className = className;
	}

	public ReverseCallGraph getGraph() {
		return graph;
	}

	public void removeClasses(Collection<MethodEntry> vertexes){
		for (MethodEntry vertex : vertexes) {
			graph.removeVertex(vertex);
		}
	}

	public void removeClass(MethodEntry vertex){
		graph.removeVertex(vertex);
	}
	/**
	 * add public methods
	 * @param className
	 * @param methodName
	 */
	public void addPublicMethod(String className, String methodName) {
		publicMethods.add(new CallContext(ResourceList
				.getClassNameFromResourcePath(className), methodName));
	}

	/**
	 * add call to the call graph
	 *
	 * @param sourceClass
	 * @param sourceMethod
	 * @param targetClass
	 * @param targetMethod
	 */

	public boolean addCall(String sourceClass, String sourceMethod,
			String targetClass, String targetMethod) {
		MethodEntry from = new MethodEntry(targetClass, targetMethod);
		MethodEntry to = new MethodEntry(sourceClass, sourceMethod);

//		logger.info("Adding new call from: " + to + " -> " + from);

		if (sourceClass.equals(className))
			cutNodes.add(to);

		if (!graph.containsEdge(from, to)) {
			graph.addEdge(from, to);
			callGraphClasses.add(targetClass.replaceAll("/", "."));
			return true;
		}
		return false;
	}

	/**
	 * @return true if the method is in the callgraph.
	 */
	public boolean hasMethod(String classname, String methodName) {
		return graph.containsVertex(new MethodEntry(classname, methodName));
	}

	/**
	 * @return true if the call is in the callgraph.
	 */
	public boolean hasCall(String owner, String methodName, String targetClass,
			String targetMethod) {

		MethodEntry from = new MethodEntry(targetClass, targetMethod);
		MethodEntry to = new MethodEntry(owner, methodName);

		return graph.getEdges().containsKey(to)
				&& graph.getEdges().get(to).contains(from);
	}

	/**
	 * @return calls exiting from the method, empty set if the call is not in
	 *         the graph.
	 */
	public Set<MethodEntry> getCallsFrom(String owner, String methodName) {
		MethodEntry call = new MethodEntry(owner, methodName);
		return getCallsFromMethod(call);
	}

	/**
	 * @return calls exiting from the method, empty set if the call is not in
	 *         the graph
	 */
	public Set<MethodEntry> getCallsFromMethod(MethodEntry call) {
		if (graph.getEdges().containsKey(call))
			return graph.getEdges().get(call);
		else {
			return new HashSet<>();
		}
	}

	/**
	 * Returns the set of public callers for the non-public executable specified by the given
	 * non-null class name and method name. Returns an empty set if no public caller could be found.
	 * If the specified callee is already public, returns a singleton set consisting of just that
	 * callee.
	 *
	 * @param className the fully-qualified class name in which the method (callee) is located
	 * @param methodName the method name + descriptor of the callee for which to find callers
	 * @return the set of public callers of the method
	 */
	public Set<MethodEntry> getPublicCallersOf(final String className, final String methodName) {
		Objects.requireNonNull(className);
		Objects.requireNonNull(methodName);
		return getPublicCallersOf(new MethodEntry(className, methodName));
	}

	public Set<MethodEntry> getPublicCallersOf(final MethodEntry callee) {
		Objects.requireNonNull(callee);
		return callerCache.computeIfAbsent(callee, this::computePublicCallers);
	}

	private Set<MethodEntry> computePublicCallers(final MethodEntry callee) {
		final Set<MethodEntry> result = new HashSet<>();
		final List<MethodEntry> visited = new LinkedList<>();
		final List<MethodDepthPair> queue =
				new LinkedList<MethodDepthPair>() {{ add(p(callee, 0)); }};

		// Using a BFS approach, determine the public callers of the given callee. We do not
		// perform an exhaustive search, but rather only explore paths up to a certain maximum
		// length, as given by Properties.MAX_RECURSION.
		while (!queue.isEmpty()) {
			final MethodDepthPair p = queue.remove(0);
			final MethodEntry current = p.method;
			final int depth = p.depth;

			visited.add(current);

			final boolean isPublic = publicMethods.stream().anyMatch(cc ->
					current.getClassName().equals(cc.getRootClassName())
							&& current.getMethodNameDesc().equals(cc.getRootMethodName()));

			if (isPublic) {
				result.add(current);
			} else if (depth < Properties.MAX_RECURSION) {
				// The call graph is reversed, the edges already point from callee to caller.
				final Set<MethodEntry> neighbors = graph.getNeighbors(current);
				for (final MethodEntry neighbor : neighbors) {
					if (!(contains(queue, neighbor) || visited.contains(neighbor))) { // avoid cycles
						queue.add(p(neighbor, depth + 1));
					}
				}
			}
		}

		return result;
	}

	private static boolean contains(final List<MethodDepthPair> queue, final MethodEntry entry) {
		for (final MethodDepthPair p : queue) {
			if (p.method.equals(entry)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * computes and returns the call contexts of the specific method
	 *
	 * @param className
	 * @param methodName
	 * @return
	 */
	public Set<CallContext> getMethodEntryPoint(String className, String methodName) {
		Set<CallContext> contexts = new HashSet<>();
		List<Call> cont = new ArrayList<>();
		cont.add(new Call(className, methodName));
		CallContext context = new CallContext(cont);
		if(publicMethods.contains(context)){
			contexts.add(context);
		}else{
			contexts.add(new CallContext());
		}
		return contexts;
	}

	/**
	 * computes and returns the call contexts that starts from the target class
	 * and end in the specific method
	 *
	 * @param className
	 * @param methodName
	 * @return
	 */
	public Set<CallContext> getAllContextsFromTargetClass(String className, String methodName) {
		MethodEntry root = new MethodEntry(className, methodName);
		Set<List<MethodEntry>> paths = PathFinder.getPahts(graph, root);
		Set<CallContext> contexts = convertIntoCallContext(paths);
		if(!Properties.EXCLUDE_IBRANCHES_CUT)
			addPublicClassMethod(className, methodName, contexts);
		return contexts;
	}

	private void addPublicClassMethod(String className, String methodName, Set<CallContext> contexts){
		List<Call> calls = new ArrayList<>();
		Call call = new Call(className, methodName);
		calls.add(call);
		CallContext context = new CallContext(calls);
		if(publicMethods.contains(context)&&className.equals(this.className))
			contexts.add(context);
	}

	private Set<CallContext> convertIntoCallContext(
			Set<List<MethodEntry>> paths) {
		Set<CallContext> contexts = new HashSet<>();

		// return only context that starts from the class under test
		for (List<MethodEntry> list : paths) {
			boolean insert = false;
			List<Call> cont = new ArrayList<>();

			for (int i = list.size() - 1; i >= 0; i--) {
				if (!insert && list.get(i).getClassName().equals(className)) {
					insert = true;
				}
				if (insert)
					cont.add(new Call(list.get(i).getClassName(), list.get(i)
							.getMethodNameDesc()));
			}
			contexts.add(new CallContext(cont));
		}
		return contexts;
	}

	/**
	 * @return the className
	 */
	public String getClassName() {
		return className;
	}


	/**
	 *
	 * @return classes reachable from the class under test
	 */
 	public Set<String>  getClassesUnderTest() {
 		if(toTestClasses.isEmpty())
 			computeInterestingClasses(graph);
 		return toTestClasses;
 	}

	/**
	 * Determine if className can be reached from the class under test
	 *
	 *
	 * @param className
	 * @return
	 */
 	public boolean isCalledClass(String className) {
 		if(toTestClasses.isEmpty())
 			computeInterestingClasses(graph);
 		if(toTestClasses.contains(className)) return true;
 		return false;
 	}

 	public boolean isCalledClassOld(String className) {
 		if(toTestClasses.contains(className)) return true;
 		if(notToTestClasses.contains(className)) return false;

		for (MethodEntry e : graph.getEdges().keySet()) {
			if (e.getClassName().equals(className)) {
				if(checkClassInPaths(this.className, graph, e))
					return true;
			}
		}
		return false;
	}

	private boolean computeInterestingClasses(Graph<MethodEntry> g) {
		Set<MethodEntry> startingVertices = new HashSet<>();
		for (MethodEntry e : graph.getVertexSet()) {
			if (e.getClassName().equals(className)) {
				startingVertices.add(e);
			}
		}
		Set<String> classes = new HashSet<>();
		Set<String> methodclasses = new HashSet<>();
		for (MethodEntry startingVertex : startingVertices) {
			PathFinderDFSIterator<MethodEntry> dfs = new PathFinderDFSIterator<>(
					g, startingVertex, true);
			while (dfs.hasNext()) {
				MethodEntry e = dfs.next();
				classes.add(e.getClassName());
				methodclasses.add(e.getClassName()+e.getMethodNameDesc());
			}
		}
		toTestMethods.addAll(methodclasses);
		toTestClasses.addAll(classes);
		return true;
	}

	private boolean checkClassInPaths(String targetClass, Graph<MethodEntry> g, MethodEntry startingVertex) {
		if(!g.containsVertex(startingVertex)){
			return false;
		}
		Set<String> classes = new HashSet<>();
		PathFinderDFSIterator<MethodEntry> dfs = new PathFinderDFSIterator<>(g, startingVertex);
		while (dfs.hasNext()) {
			MethodEntry e = dfs.next();
			classes.add(e.getClassName());
			if(e.getClassName().equals(targetClass)){
				toTestClasses.addAll(classes);
				return true;
			}
		}
		notToTestClasses.addAll(classes);
		return false;
	}

	/**
	 * Determine if methodName of className can be called through the target
	 * class
	 *
	 * @param className
	 * @param methodName
	 * @return
	 */
	public boolean isCalledMethod(String className, String methodName) {
		if (toTestMethods.isEmpty())
			computeInterestingClasses(graph);
		if (toTestMethods.contains(className + methodName)) {
			return true;
		}
		return false;
	}

	public boolean isCalledMethodOld(String className, String methodName) {


		MethodEntry tmp = new MethodEntry(className, methodName);
		for (MethodEntry e : graph.getEdges().keySet()) {
			if (e.equals(tmp)) {
				for (List<MethodEntry> c : PathFinder.getPahts(graph, e)) {
					for (MethodEntry entry : c) {
						if (entry.getClassName().equals(this.className))
							return true;
					}
				}
			}
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Iterable#iterator()
	 */
	@Override
	public Iterator<MethodEntry> iterator() {
		return graph.getVertexSet().iterator();
	}

	/**
	 * @return a copy of the current vertexset
	 */
	public Set<MethodEntry> getViewOfCurrentMethods() {
		return new LinkedHashSet<MethodEntry>(graph.getVertexSet());
	}

	/**
	 * @return set of class names.
	 */
	public Set<String> getClasses() {
		return callGraphClasses;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		for (MethodEntry caller : graph.getVertexSet()) {
			for (MethodEntry callee : graph.getReverseNeighbors(caller)) {
				sb.append(caller).append(" -> ").append(callee).append("\n");
			}
		}
		return sb.toString();
	}

	static final class MethodDepthPair {
		private final MethodEntry method;
		private final int depth;

		MethodDepthPair(final MethodEntry method, final int depth) {
			this.method = method;
			this.depth = depth;
		}

		static MethodDepthPair p(final MethodEntry method, final int depth) {
			return new MethodDepthPair(method, depth);
		}
	}
}