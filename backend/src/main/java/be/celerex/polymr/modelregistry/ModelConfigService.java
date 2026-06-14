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

package be.celerex.polymr.modelregistry;

import be.celerex.polymr.model.AiModel;
import be.celerex.polymr.security.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class ModelConfigService {
	@Inject
	ObjectMapper objectMapper;

	@Inject
	SecretCipher secretCipher;

	public Map<String, Object> resolveConfig(AiModel model) {
		Map<String, Object> config = new HashMap<>();
		if (model == null || model.configJson == null || !model.configJson.isObject()) {
			return config;
		}
		model.configJson
			.fields()
			.forEachRemaining(
				entry -> {
					JsonNode node = entry.getValue();
					if (node.isObject() && node.has("ciphertext") && node.has("nonce")) {
						String cipher = node.get("ciphertext").asText();
						String nonce = node.get("nonce").asText();
						String value = secretCipher.decrypt(new SecretCipher.EncryptedSecret(cipher, nonce));
						config.put(entry.getKey(), value);
					}
					else if (node.isTextual()) {
						config.put(entry.getKey(), node.asText());
					}
					else if (node.isNumber()) {
						config.put(entry.getKey(), node.numberValue());
					}
					else if (node.isBoolean()) {
						config.put(entry.getKey(), node.booleanValue());
					}
					else {
						config.put(entry.getKey(), objectMapper.convertValue(node, Object.class));
					}
				}
			);
		return config;
	}
}
