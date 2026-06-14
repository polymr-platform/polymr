/*
* Copyright (C) 2026 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.celerex.polymr.modelregistry.provider;

import be.celerex.polymr.modelregistry.dto.ProviderProperty;
import be.celerex.polymr.modelregistry.dto.ProviderPropertyType;
import java.lang.reflect.Method;
import java.util.List;
import org.jboss.logging.Logger;

public class ModelBehaviorConfig {
	static final String INCLUDE_REASONING_TOKENS = "include_reasoning_tokens";
	static final String THINKING_LEVEL = "thinking_level";
	static final String REASONING_MAX_TOKENS = "reasoning_max_tokens";
	static final String MAX_OUTPUT_TOKENS = "max_output_tokens";
	private static final Logger LOGGER = Logger.getLogger(ModelBehaviorConfig.class);

	private ModelBehaviorConfig() {}

	public static List<ProviderProperty> properties() {
		return List.of(
			new ProviderProperty(
				INCLUDE_REASONING_TOKENS,
				"Include reasoning tokens",
				ProviderPropertyType.BOOLEAN,
				false,
				"false",
				"When enabled, the model includes reasoning tokens in the response (supported by select models).",
				"false",
				null,
				true,
				null,
				null,
				null
			),
			new ProviderProperty(
				THINKING_LEVEL,
				"Thinking level",
				ProviderPropertyType.STRING,
				false,
				"none",
				"Optional reasoning effort/verbosity level. Common values: none, minimal, low, medium, high, xhigh.",
				"none",
				List.of("none", "minimal", "low", "medium", "high", "xhigh"),
				true,
				null,
				null,
				null
			),
			new ProviderProperty(
				REASONING_MAX_TOKENS,
				"Max reasoning tokens",
				ProviderPropertyType.NUMBER,
				false,
				"1024",
				"Optional cap for reasoning tokens. A good default is 1024.",
				null,
				null,
				true,
				0.0,
				null,
				1.0
			),
			new ProviderProperty(
				MAX_OUTPUT_TOKENS,
				"Max output tokens",
				ProviderPropertyType.NUMBER,
				false,
				null,
				"Optional cap for completion/output tokens.",
				null,
				null,
				true,
				0.0,
				null,
				1.0
			)
		);
	}

	static void apply(Object builder, ProviderConfig cfg) {
		if (builder == null || cfg == null) {
			return;
		}
		Boolean include = cfg.bool(INCLUDE_REASONING_TOKENS);
		if (include != null && include) {
			applyReasoningTokens(builder, include);
		}
		String thinking = cfg.string(THINKING_LEVEL);
		if (thinking != null && !thinking.isBlank() && !"none".equalsIgnoreCase(thinking.trim())) {
			applyThinkingLevel(builder, thinking.trim());
		}
		Integer maxTokens = cfg.integer(REASONING_MAX_TOKENS);
		if (maxTokens != null && maxTokens > 0) {
			applyReasoningMaxTokens(builder, maxTokens);
		}
		Integer maxOutputTokens = cfg.integer(MAX_OUTPUT_TOKENS);
		if (maxOutputTokens != null && maxOutputTokens > 0) {
			applyMaxOutputTokens(builder, maxOutputTokens);
		}
	}

	private static void applyReasoningTokens(Object builder, boolean value) {
		invokeIfPresent(builder, "includeReasoning", value);
		invokeIfPresent(builder, "includeReasoningTokens", value);
		invokeIfPresent(builder, "reasoningTokens", value);
		invokeIfPresent(builder, "includeThinkingTokens", value);
	}

	private static void applyThinkingLevel(Object builder, String value) {
		if (invokeIfPresent(builder, "reasoningEffort", value)) {
			return;
		}
		if (invokeIfPresent(builder, "thinking", value)) {
			return;
		}
		if (invokeIfPresent(builder, "thinkingLevel", value)) {
			return;
		}
		if (invokeIfPresent(builder, "reasoningLevel", value)) {
			return;
		}
	}

	private static void applyReasoningMaxTokens(Object builder, int value) {
		if (invokeIfPresent(builder, "maxReasoningTokens", value)) {
			return;
		}
		if (invokeIfPresent(builder, "reasoningMaxTokens", value)) {
			return;
		}
		if (invokeIfPresent(builder, "maxThinkingTokens", value)) {
			return;
		}
		invokeIfPresent(builder, "thinkingMaxTokens", value);
	}

	private static void applyMaxOutputTokens(Object builder, int value) {
		if (invokeNumeric(builder, "maxOutputTokens", value)) {
			return;
		}
		// Prefer maxCompletionTokens before maxTokens because some OpenAI builders expose both,
		// and selecting maxTokens first sends the deprecated max_tokens parameter for newer models.
		if (invokeNumeric(builder, "maxCompletionTokens", value)) {
			return;
		}
		if (invokeNumeric(builder, "maxTokens", value)) {
			return;
		}
		if (invokeNumeric(builder, "maxTokensToSample", value)) {
			return;
		}
		if (invokeNumeric(builder, "maxTokensToGenerate", value)) {
			return;
		}
		invokeNumeric(builder, "maxNewTokens", value);
	}

	private static boolean invokeIfPresent(Object target, String methodName, boolean value) {
		try {
			Method method = target.getClass().getMethod(methodName, boolean.class);
			method.invoke(target, value);
			return true;
		}
		catch (NoSuchMethodException ignored) {
			return false;
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to apply %s", methodName);
			return false;
		}
	}

	private static boolean invokeIfPresent(Object target, String methodName, int value) {
		try {
			Method method = target.getClass().getMethod(methodName, int.class);
			method.invoke(target, value);
			return true;
		}
		catch (NoSuchMethodException ignored) {
			return false;
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to apply %s", methodName);
			return false;
		}
	}

	private static boolean invokeIfPresent(Object target, String methodName, long value) {
		try {
			Method method = target.getClass().getMethod(methodName, long.class);
			method.invoke(target, value);
			return true;
		}
		catch (NoSuchMethodException ignored) {
			return false;
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to apply %s", methodName);
			return false;
		}
	}

	private static boolean invokeIfPresent(Object target, String methodName, Integer value) {
		try {
			Method method = target.getClass().getMethod(methodName, Integer.class);
			method.invoke(target, value);
			return true;
		}
		catch (NoSuchMethodException ignored) {
			return false;
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to apply %s", methodName);
			return false;
		}
	}

	private static boolean invokeIfPresent(Object target, String methodName, Long value) {
		try {
			Method method = target.getClass().getMethod(methodName, Long.class);
			method.invoke(target, value);
			return true;
		}
		catch (NoSuchMethodException ignored) {
			return false;
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to apply %s", methodName);
			return false;
		}
	}

	private static boolean invokeIfPresent(Object target, String methodName, Double value) {
		try {
			Method method = target.getClass().getMethod(methodName, Double.class);
			method.invoke(target, value);
			return true;
		}
		catch (NoSuchMethodException ignored) {
			return false;
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to apply %s", methodName);
			return false;
		}
	}

	private static boolean invokeNumeric(Object target, String methodName, int value) {
		return invokeIfPresent(target, methodName, value)
			|| invokeIfPresent(target, methodName, Integer.valueOf(value))
			|| invokeIfPresent(target, methodName, (long) value)
			|| invokeIfPresent(target, methodName, Long.valueOf(value))
			|| invokeIfPresent(target, methodName, Double.valueOf(value));
	}

	private static boolean invokeIfPresent(Object target, String methodName, String value) {
		try {
			Method method = target.getClass().getMethod(methodName, String.class);
			method.invoke(target, value);
			return true;
		}
		catch (NoSuchMethodException ignored) {
			return invokeEnumIfPresent(target, methodName, value);
		}
		catch (Exception ex) {
			LOGGER.debugf(ex, "Failed to apply %s", methodName);
			return false;
		}
	}

	private static boolean invokeEnumIfPresent(Object target, String methodName, String value) {
		for (Method method : target.getClass()
			.getMethods()) {
			if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
				continue;
			}
			Class<?> param = method.getParameterTypes()[0];
			if (!param.isEnum()) {
				continue;
			}
			Object enumValue = resolveEnumValue(param, value);
			if (enumValue == null) {
				return false;
			}
			try {
				method.invoke(target, enumValue);
				return true;
			}
			catch (Exception ex) {
				LOGGER.debugf(ex, "Failed to apply %s enum", methodName);
				return false;
			}
		}
		return false;
	}

	private static Object resolveEnumValue(Class<?> enumType, String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String normalized = value.trim().toUpperCase();
		for (Object constant : enumType.getEnumConstants()) {
			if (constant != null && constant.toString().equalsIgnoreCase(normalized)) {
				return constant;
			}
		}
		return null;
	}
}
