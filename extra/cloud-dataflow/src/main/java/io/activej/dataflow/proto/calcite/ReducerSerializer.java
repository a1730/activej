package io.activej.dataflow.proto.calcite;

import com.google.protobuf.InvalidProtocolBufferException;
import io.activej.dataflow.calcite.aggregation.*;
import io.activej.serializer.BinaryInput;
import io.activej.serializer.BinaryOutput;
import io.activej.serializer.BinarySerializer;
import io.activej.serializer.CorruptedDataException;

import java.util.ArrayList;
import java.util.List;

import static io.activej.serializer.BinarySerializers.BYTES_SERIALIZER;


public final class ReducerSerializer implements BinarySerializer<RecordReducer> {
	@Override
	public void encode(BinaryOutput out, RecordReducer recordReducer) {
		BYTES_SERIALIZER.encode(out, convert(recordReducer).toByteArray());
	}

	@Override
	public RecordReducer decode(BinaryInput in) throws CorruptedDataException {
		byte[] serialized = BYTES_SERIALIZER.decode(in);

		ReducerProto.RecordReducer predicate;
		try {
			predicate = ReducerProto.RecordReducer.parseFrom(serialized);
		} catch (InvalidProtocolBufferException e) {
			throw new CorruptedDataException(e.getMessage());
		}

		return convert(predicate);
	}

	private static ReducerProto.RecordReducer convert(RecordReducer recordReducer) {
		return ReducerProto.RecordReducer.newBuilder()
				.addAllFieldReducers(recordReducer.getReducers().stream()
						.map(ReducerSerializer::convert)
						.toList())
				.build();
	}

	private static ReducerProto.RecordReducer.FieldReducer convert(FieldReducer<?, ?, ?> fieldReducer) {
		ReducerProto.RecordReducer.FieldReducer.Builder builder = ReducerProto.RecordReducer.FieldReducer.newBuilder();
		int fieldIndex = fieldReducer.getFieldIndex();

		if (fieldReducer instanceof CountReducer<?>) {
			builder.setCountReducer(ReducerProto.RecordReducer.FieldReducer.CountReducer.newBuilder()
					.setFieldIndex(fieldIndex));
		} else if (fieldReducer instanceof SumReducerInteger<?>) {
			builder.setSumReducerInteger(ReducerProto.RecordReducer.FieldReducer.SumReducerInteger.newBuilder()
					.setFieldIndex(fieldIndex));
		} else if (fieldReducer instanceof SumReducerDecimal<?>) {
			builder.setSumReducerDecimal(ReducerProto.RecordReducer.FieldReducer.SumReducerDecimal.newBuilder()
					.setFieldIndex(fieldIndex));
		} else if (fieldReducer instanceof AvgReducer) {
			builder.setAvgReducer(ReducerProto.RecordReducer.FieldReducer.AvgReducer.newBuilder()
					.setFieldIndex(fieldIndex));
		} else if (fieldReducer instanceof MinReducer) {
			builder.setMinReducer(ReducerProto.RecordReducer.FieldReducer.MinReducer.newBuilder()
					.setFieldIndex(fieldIndex));
		} else if (fieldReducer instanceof MaxReducer) {
			builder.setMaxReducer(ReducerProto.RecordReducer.FieldReducer.MaxReducer.newBuilder()
					.setFieldIndex(fieldIndex));
		} else {
			throw new IllegalArgumentException("Unknown field recducer type: " + fieldReducer.getClass());
		}

		return builder.build();
	}

	private static RecordReducer convert(ReducerProto.RecordReducer recordReducer) {
		List<FieldReducer<?, ?, ?>> fieldReducers = new ArrayList<>();
		for (ReducerProto.RecordReducer.FieldReducer x : recordReducer.getFieldReducersList()) {
			fieldReducers.add(convert(x));
		}
		return new RecordReducer(fieldReducers);
	}

	private static FieldReducer<?, ?, ?> convert(ReducerProto.RecordReducer.FieldReducer fieldReducer) {
		return switch (fieldReducer.getFieldReducerCase()){
			case COUNT_REDUCER -> new CountReducer<>(fieldReducer.getCountReducer().getFieldIndex());
			case SUM_REDUCER_INTEGER -> new SumReducerInteger<>(fieldReducer.getSumReducerInteger().getFieldIndex());
			case SUM_REDUCER_DECIMAL -> new SumReducerDecimal<>(fieldReducer.getSumReducerDecimal().getFieldIndex());
			case AVG_REDUCER -> new AvgReducer(fieldReducer.getAvgReducer().getFieldIndex());
			case MIN_REDUCER -> new MinReducer<>(fieldReducer.getMinReducer().getFieldIndex());
			case MAX_REDUCER -> new MaxReducer<>(fieldReducer.getMaxReducer().getFieldIndex());
			case FIELDREDUCER_NOT_SET -> throw new CorruptedDataException("Field predicate was not set");
		};
	}

}
