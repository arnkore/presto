/*
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
package com.facebook.presto.operator.aggregation;

import com.facebook.presto.bytecode.DynamicClassLoader;
import com.facebook.presto.metadata.BoundVariables;
import com.facebook.presto.metadata.FunctionRegistry;
import com.facebook.presto.metadata.SqlAggregationFunction;
import com.facebook.presto.operator.aggregation.state.NullableBooleanState;
import com.facebook.presto.operator.aggregation.state.NullableDoubleState;
import com.facebook.presto.operator.aggregation.state.NullableLongState;
import com.facebook.presto.operator.aggregation.state.ObjectBlockPositionState;
import com.facebook.presto.operator.aggregation.state.ObjectBlockPositionStateSerializer;
import com.facebook.presto.operator.aggregation.state.SliceBlockPositionState;
import com.facebook.presto.operator.aggregation.state.SliceBlockPositionStateSerializer;
import com.facebook.presto.operator.aggregation.state.StateCompiler;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.function.AccumulatorState;
import com.facebook.presto.spi.function.AccumulatorStateSerializer;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;

import java.lang.invoke.MethodHandle;
import java.util.List;

import static com.facebook.presto.metadata.Signature.typeVariable;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INDEX;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INPUT_CHANNEL;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.STATE;
import static com.facebook.presto.operator.aggregation.AggregationUtils.generateAggregationName;
import static com.facebook.presto.spi.type.TypeSignature.parseTypeSignature;
import static com.facebook.presto.util.Reflection.methodHandle;
import static com.google.common.collect.ImmutableList.toImmutableList;

public class ArbitraryAggregationFunction
        extends SqlAggregationFunction
{
    public static final ArbitraryAggregationFunction ARBITRARY_AGGREGATION = new ArbitraryAggregationFunction();
    private static final String NAME = "arbitrary";

    private static final MethodHandle LONG_INPUT_FUNCTION = methodHandle(ArbitraryAggregationFunction.class, "input", Type.class, NullableLongState.class, Block.class, int.class);
    private static final MethodHandle DOUBLE_INPUT_FUNCTION = methodHandle(ArbitraryAggregationFunction.class, "input", Type.class, NullableDoubleState.class, Block.class, int.class);
    private static final MethodHandle SLICE_INPUT_FUNCTION = methodHandle(ArbitraryAggregationFunction.class, "input", Type.class, SliceBlockPositionState.class, Block.class, int.class);
    private static final MethodHandle BOOLEAN_INPUT_FUNCTION = methodHandle(ArbitraryAggregationFunction.class, "input", Type.class, NullableBooleanState.class, Block.class, int.class);
    private static final MethodHandle BLOCK_INPUT_FUNCTION = methodHandle(ArbitraryAggregationFunction.class, "input", Type.class, ObjectBlockPositionState.class, Block.class, int.class);

    private static final MethodHandle LONG_OUTPUT_FUNCTION = methodHandle(NullableLongState.class, "write", Type.class, NullableLongState.class, BlockBuilder.class);
    private static final MethodHandle DOUBLE_OUTPUT_FUNCTION = methodHandle(NullableDoubleState.class, "write", Type.class, NullableDoubleState.class, BlockBuilder.class);
    private static final MethodHandle SLICE_OUTPUT_FUNCTION = methodHandle(SliceBlockPositionState.class, "write", Type.class, SliceBlockPositionState.class, BlockBuilder.class);
    private static final MethodHandle BOOLEAN_OUTPUT_FUNCTION = methodHandle(NullableBooleanState.class, "write", Type.class, NullableBooleanState.class, BlockBuilder.class);
    private static final MethodHandle BLOCK_OUTPUT_FUNCTION = methodHandle(ObjectBlockPositionState.class, "write", Type.class, ObjectBlockPositionState.class, BlockBuilder.class);

    private static final MethodHandle LONG_COMBINE_FUNCTION = methodHandle(ArbitraryAggregationFunction.class, "combine", NullableLongState.class, NullableLongState.class);
    private static final MethodHandle DOUBLE_COMBINE_FUNCTION = methodHandle(ArbitraryAggregationFunction.class, "combine", NullableDoubleState.class, NullableDoubleState.class);
    private static final MethodHandle SLICE_COMBINE_FUNCTION = methodHandle(ArbitraryAggregationFunction.class, "combine", SliceBlockPositionState.class, SliceBlockPositionState.class);
    private static final MethodHandle BOOLEAN_COMBINE_FUNCTION = methodHandle(ArbitraryAggregationFunction.class, "combine", NullableBooleanState.class, NullableBooleanState.class);
    private static final MethodHandle BLOCK_COMBINE_FUNCTION = methodHandle(ArbitraryAggregationFunction.class, "combine", ObjectBlockPositionState.class, ObjectBlockPositionState.class);

    protected ArbitraryAggregationFunction()
    {
        super(NAME,
                ImmutableList.of(typeVariable("T")),
                ImmutableList.of(),
                parseTypeSignature("T"),
                ImmutableList.of(parseTypeSignature("T")));
    }

    @Override
    public String getDescription()
    {
        return "return an arbitrary non-null input value";
    }

    @Override
    public InternalAggregationFunction specialize(BoundVariables boundVariables, int arity, TypeManager typeManager, FunctionRegistry functionRegistry)
    {
        Type valueType = boundVariables.getTypeVariable("T");
        return generateAggregation(valueType);
    }

    private static InternalAggregationFunction generateAggregation(Type type)
    {
        DynamicClassLoader classLoader = new DynamicClassLoader(ArbitraryAggregationFunction.class.getClassLoader());

        List<Type> inputTypes = ImmutableList.of(type);

        MethodHandle inputFunction;
        MethodHandle combineFunction;
        MethodHandle outputFunction;
        Class<? extends AccumulatorState> stateInterface;
        AccumulatorStateSerializer<?> stateSerializer;

        if (type.getJavaType() == long.class) {
            stateInterface = NullableLongState.class;
            stateSerializer = StateCompiler.generateStateSerializer(stateInterface, classLoader);
            inputFunction = LONG_INPUT_FUNCTION;
            combineFunction = LONG_COMBINE_FUNCTION;
            outputFunction = LONG_OUTPUT_FUNCTION;
        }
        else if (type.getJavaType() == double.class) {
            stateInterface = NullableDoubleState.class;
            stateSerializer = StateCompiler.generateStateSerializer(stateInterface, classLoader);
            inputFunction = DOUBLE_INPUT_FUNCTION;
            combineFunction = DOUBLE_COMBINE_FUNCTION;
            outputFunction = DOUBLE_OUTPUT_FUNCTION;
        }
        else if (type.getJavaType() == Slice.class) {
            stateInterface = SliceBlockPositionState.class;
            stateSerializer = new SliceBlockPositionStateSerializer(type);
            inputFunction = SLICE_INPUT_FUNCTION;
            combineFunction = SLICE_COMBINE_FUNCTION;
            outputFunction = SLICE_OUTPUT_FUNCTION;
        }
        else if (type.getJavaType() == boolean.class) {
            stateInterface = NullableBooleanState.class;
            stateSerializer = StateCompiler.generateStateSerializer(stateInterface, classLoader);
            inputFunction = BOOLEAN_INPUT_FUNCTION;
            combineFunction = BOOLEAN_COMBINE_FUNCTION;
            outputFunction = BOOLEAN_OUTPUT_FUNCTION;
        }
        else {
            stateInterface = ObjectBlockPositionState.class;
            stateSerializer = new ObjectBlockPositionStateSerializer(type);
            inputFunction = BLOCK_INPUT_FUNCTION;
            combineFunction = BLOCK_COMBINE_FUNCTION;
            outputFunction = BLOCK_OUTPUT_FUNCTION;
        }
        inputFunction = inputFunction.bindTo(type);

        Type intermediateType = stateSerializer.getSerializedType();
        List<ParameterMetadata> inputParameterMetadata = createInputParameterMetadata(type);
        AggregationMetadata metadata = new AggregationMetadata(
                generateAggregationName(NAME, type.getTypeSignature(), inputTypes.stream().map(Type::getTypeSignature).collect(toImmutableList())),
                inputParameterMetadata,
                inputFunction,
                combineFunction,
                outputFunction.bindTo(type),
                stateInterface,
                stateSerializer,
                StateCompiler.generateStateFactory(stateInterface, classLoader),
                type);

        GenericAccumulatorFactoryBinder factory = AccumulatorCompiler.generateAccumulatorFactoryBinder(metadata, classLoader);
        return new InternalAggregationFunction(NAME, inputTypes, intermediateType, type, true, false, factory);
    }

    private static List<ParameterMetadata> createInputParameterMetadata(Type value)
    {
        return ImmutableList.of(new ParameterMetadata(STATE), new ParameterMetadata(BLOCK_INPUT_CHANNEL, value), new ParameterMetadata(BLOCK_INDEX));
    }

    public static void input(Type type, NullableDoubleState state, Block block, int position)
    {
        if (!state.isNull()) {
            return;
        }
        state.setNull(false);
        state.setDouble(type.getDouble(block, position));
    }

    public static void input(Type type, NullableLongState state, Block block, int position)
    {
        if (!state.isNull()) {
            return;
        }
        state.setNull(false);
        state.setLong(type.getLong(block, position));
    }

    public static void input(Type type, SliceBlockPositionState state, Block block, int position)
    {
        if (state.getBlock() != null) {
            return;
        }
        state.setBlock(block);
        state.setPosition(position);
    }

    public static void input(Type type, NullableBooleanState state, Block block, int position)
    {
        if (!state.isNull()) {
            return;
        }
        state.setNull(false);
        state.setBoolean(type.getBoolean(block, position));
    }

    public static void input(Type type, ObjectBlockPositionState state, Block block, int position)
    {
        if (state.getBlock() != null) {
            return;
        }
        state.setBlock(block);
        state.setPosition(position);
    }

    public static void combine(NullableLongState state, NullableLongState otherState)
    {
        if (!state.isNull()) {
            return;
        }
        state.setNull(false);
        state.setLong(otherState.getLong());
    }

    public static void combine(NullableDoubleState state, NullableDoubleState otherState)
    {
        if (!state.isNull()) {
            return;
        }
        state.setNull(false);
        state.setDouble(otherState.getDouble());
    }

    public static void combine(NullableBooleanState state, NullableBooleanState otherState)
    {
        if (!state.isNull()) {
            return;
        }
        state.setNull(false);
        state.setBoolean(otherState.getBoolean());
    }

    public static void combine(SliceBlockPositionState state, SliceBlockPositionState otherState)
    {
        if (state.getBlock() != null) {
            return;
        }
        state.setBlock(otherState.getBlock());
        state.setPosition(otherState.getPosition());
    }

    public static void combine(ObjectBlockPositionState state, ObjectBlockPositionState otherState)
    {
        if (state.getBlock() != null) {
            return;
        }
        state.setBlock(otherState.getBlock());
        state.setPosition(otherState.getPosition());
    }
}
