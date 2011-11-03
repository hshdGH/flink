package eu.stratosphere.sopremo.cleansing.fusion;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import eu.stratosphere.nephele.configuration.Configuration;
import eu.stratosphere.pact.common.plan.PactModule;
import eu.stratosphere.sopremo.ElementaryOperator;
import eu.stratosphere.sopremo.EvaluationContext;
import eu.stratosphere.sopremo.Name;
import eu.stratosphere.sopremo.Property;
import eu.stratosphere.sopremo.cleansing.scrubbing.RuleManager;
import eu.stratosphere.sopremo.expressions.EvaluationExpression;
import eu.stratosphere.sopremo.expressions.ObjectCreation;
import eu.stratosphere.sopremo.pact.JsonCollector;
import eu.stratosphere.sopremo.pact.SopremoMap;
import eu.stratosphere.sopremo.type.ArrayNode;
import eu.stratosphere.sopremo.type.JsonNode;
import eu.stratosphere.sopremo.type.NullNode;
import eu.stratosphere.sopremo.type.ObjectNode;

/**
 * Input elements are either
 * <ul>
 * <li>Array of records resulting from record linkage without transitive closure. [r1, r2, r3] with r<sub>i</sub>
 * <li>Array of record clusters resulting from record linkage with transitive closure
 * </ul>
 * 
 * @author Arvid Heise
 */
@Name(verb = "fuse")
public class Fusion extends ElementaryOperator<Fusion> {
	/**
	 * 
	 */
	private static final long serialVersionUID = 8429199636646276642L;

	private final List<Object2DoubleMap<List<String>>> weights = new ArrayList<Object2DoubleMap<List<String>>>();

	private RuleManager rules = new RuleManager();
	
	private boolean multipleRecordsPerSource = false;

	private FusionRule defaultValueRule = MergeRule.INSTANCE;


	@Override
	public PactModule asPactModule(final EvaluationContext context) {
		if (this.rules.isEmpty())
			return new PactModule(this.getName(), 1, 1);
		return super.asPactModule(context);
	}

	public FusionRule getDefaultValueRule() {
		return this.defaultValueRule;
	}


	private Object2DoubleMap<List<String>> getWeightMap(final int inputIndex) {
		Object2DoubleMap<List<String>> weightMap = this.weights.get(inputIndex);
		if (weightMap == null) {
			while (this.weights.size() <= inputIndex)
				this.weights.add(null);
			this.weights.set(inputIndex, weightMap = new Object2DoubleOpenHashMap<List<String>>());
			weightMap.defaultReturnValue(1);
		}
		return weightMap;
	}

	public double getWeights(final int inputIndex, final String... path) {
		return this.getWeightMap(inputIndex).getDouble(Arrays.asList(path));
	}

	public boolean isMultipleRecordsPerSource() {
		return this.multipleRecordsPerSource;
	}

	public void addRule(EvaluationExpression rule, List<EvaluationExpression> target) {
		rules.addRule(rule, target);
	}

	public void addRule(EvaluationExpression rule, EvaluationExpression... target) {
		rules.addRule(rule, target);
	}

	public void removeRule(EvaluationExpression rule, List<EvaluationExpression> target) {
		rules.removeRule(rule, target);
	}

	public void removeRule(EvaluationExpression rule, EvaluationExpression... target) {
		rules.removeRule(rule, target);
	}

	public void setDefaultValueRule(final FusionRule defaultValueRule) {
		if (defaultValueRule == null)
			throw new NullPointerException("defaultValueRule must not be null");

		this.defaultValueRule = defaultValueRule;
	}

	public void setMultipleRecordsPerSource(final boolean multipleRecordsPerSource) {
		this.multipleRecordsPerSource = multipleRecordsPerSource;
	}

	public void setWeight(final double weight, final int inputIndex, final String... path) {
		this.getWeightMap(inputIndex).put(Arrays.asList(path), weight);
	}

	@Property
	@Name(preposition = "into")
	public void setRuleExpression(ObjectCreation ruleExpression) {
		System.out.println(ruleExpression);
//		this.rules.parse(ruleExpression, );
		// extractRules(ruleExpression, EvaluationExpression.VALUE);
	}

	public ObjectCreation getRuleExpression() {
		return new ObjectCreation();
	}

	@Property
	@Name(preposition = "with weights")
	public void setWeightExpression(ObjectCreation ruleExpression) {
		System.out.println(ruleExpression);
//		this.rules.clear();
		// extractRules(ruleExpression, EvaluationExpression.VALUE);
	}

	public ObjectCreation getWeightExpression() {
		return new ObjectCreation();
	}

	@Property
	@Name(verb = "update")
	public void setUpdateExpression(ObjectCreation ruleExpression) {
		System.out.println(ruleExpression);
//		this.rules.clear();
		// extractRules(ruleExpression, EvaluationExpression.VALUE);
	}

	@Property
	@Name(verb = "update")
	public ObjectCreation getUpdateExpression() {
		return new ObjectCreation();
	}

	public static class Implementation extends
			SopremoMap<JsonNode, JsonNode, JsonNode, JsonNode> {
		private Map<List<String>, FusionRule> rules;

		private List<Object2DoubleMap<List<String>>> weights;

		private FusionContext context;

		private boolean multipleRecordsPerSource;

		private FusionRule defaultValueRule;

		private transient List<JsonNode> contextNodes = new ArrayList<JsonNode>();

		@Override
		public void configure(final Configuration parameters) {
			super.configure(parameters);

			this.context = new FusionContext(this.getContext());
			for (int index = 0; index < this.weights.size(); index++)
				if (this.weights.get(index) == null || this.weights.get(index).isEmpty()) {
					final Object2DoubleMap<List<String>> quickMap = Object2DoubleMaps.singleton(null, null);
					quickMap.defaultReturnValue(1);
					this.weights.set(index, quickMap);
				}
		}

		private JsonNode findFirstNode(final JsonNode[] values) {
			JsonNode firstNonNull = NullNode.getInstance();
			for (final JsonNode value : values)
				if (value != NullNode.getInstance()) {
					firstNonNull = value;
					break;
				}
			return firstNonNull;
		}

		private JsonNode fuse(final JsonNode[] values, final double[] weights, final List<String> currentPath) {
			final FusionRule fusionRule = this.rules.get(currentPath);

			if (fusionRule != null)
				return fusionRule.fuse(values, weights, this.context);

			final JsonNode firstNonNull = this.findFirstNode(values);
			if (firstNonNull == NullNode.getInstance())
				return firstNonNull;

			if (firstNonNull.isObject())
				return this.fuseObjects(values, weights, currentPath, ((ObjectNode) firstNonNull).getFieldNames());

			if (firstNonNull.isArray())
				return this.fuseArrays(values);

			return this.defaultValueRule.fuse(values, weights, this.context);
		}

		private JsonNode fuseArrays(final JsonNode[] values) {
			final ArrayNode fusedArray = new ArrayNode();
			for (final JsonNode array : values)
				for (final JsonNode element : (ArrayNode) array)
					fusedArray.add(element);
			return fusedArray;
		}

		private JsonNode fuseObjects(final JsonNode[] values, final double[] weights, final List<String> currentPath,
				final Iterator<String> fieldNames) {
			final JsonNode[] children = new JsonNode[values.length];
			final List<String> childPath = new ArrayList<String>(currentPath);
			final double[] childWeights = new double[weights.length];

			final ObjectNode fusedObject = new ObjectNode();
			final int lastPath = childPath.size();
			childPath.add(null);
			while (fieldNames.hasNext()) {
				final String fieldName = fieldNames.next();

				for (int index = 0; index < values.length; index++) {
					children[index] = values[index] == NullNode.getInstance() ? values[index]
						: ((ObjectNode) values[index])
							.get(fieldName);
					childWeights[index] = weights[index] * this.getWeight(index, childPath);
				}

				childPath.set(lastPath, fieldName);
				fusedObject.put(fieldName, this.fuse(children, childWeights, childPath));
			}

			return fusedObject;
		}

		private Double getWeight(final int index, final List<?> path) {
			return this.weights.get(this.context.getSourceIndexes()[index]).get(path);
		}

		@Override
		protected void map(final JsonNode key, final JsonNode values, final JsonCollector out) {
			try {
				this.contextNodes.clear();
				if (this.multipleRecordsPerSource) {
					final Iterator<JsonNode> iterator = ((ArrayNode) values).iterator();
					final IntList sourceIndexes = new IntArrayList();
					for (int sourceIndex = 0; iterator.hasNext(); sourceIndex++)
						for (final JsonNode value : (ArrayNode) iterator.next()) {
							this.contextNodes.add(value);
							sourceIndexes.add(sourceIndex);
						}

					this.context.setSourceIndexes(sourceIndexes.toIntArray());
				} else {
					for (final JsonNode value : (ArrayNode) values)
						this.contextNodes.add(value);

					final int[] sourceIndexes = new int[this.contextNodes.size()];
					for (int index = 0; index < sourceIndexes.length; index++)
						sourceIndexes[index] = index;

					this.context.setSourceIndexes(sourceIndexes);
				}

				this.context.setContextNodes(this.contextNodes.toArray(new JsonNode[this.contextNodes.size()]));
				final double[] initialWeights = new double[this.contextNodes.size()];
				for (int index = 0; index < initialWeights.length; index++)
					initialWeights[index] = this.getWeight(index, Collections.EMPTY_LIST);
				out.collect(key, this.fuse(this.context.getContextNodes(), initialWeights, new ArrayList<String>()));
			} catch (final UnresolvableEvaluationException e) {
				// do not emit invalid record
			}
		}
	}

}
