/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.cube.linear;

import io.activej.aggregation.ot.AggregationStructure;
import io.activej.common.exception.MalformedDataException;
import io.activej.cube.CubeStructure;

import java.util.List;
import java.util.Set;

import static io.activej.common.Utils.not;

public interface MeasuresValidator {

	void validate(String aggregationId, List<String> measures) throws MalformedDataException;

	static MeasuresValidator ofCubeStructure(CubeStructure cubeStructure) {
		return (aggregationId, measures) -> {
			AggregationStructure aggregationStructure = cubeStructure.getAggregationStructure(aggregationId);
			if (aggregationStructure == null) {
				throw new MalformedDataException("Unknown aggregation: " + aggregationId);
			}
			Set<String> allowedMeasures = aggregationStructure.getMeasureTypes().keySet();
			List<String> unknownMeasures = measures.stream()
				.filter(not(allowedMeasures::contains))
				.toList();
			if (!unknownMeasures.isEmpty()) {
				throw new MalformedDataException(String.format("Unknown measures %s in aggregation '%s'", unknownMeasures, aggregationId));
			}
		};
	}
}
