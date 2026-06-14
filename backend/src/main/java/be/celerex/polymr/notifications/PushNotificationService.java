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

package be.celerex.polymr.notifications;

import be.celerex.polymr.model.PushSubscription;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.List;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

@ApplicationScoped
public class PushNotificationService {
	private static final Logger LOGGER = Logger.getLogger(PushNotificationService.class);
	@Inject
	VapidKeyService vapidKeyService;

	@org.eclipse.microprofile.config.inject.ConfigProperty(
	name = "polymr.push.vapid.subject",
	defaultValue = "mailto:admin@polymr.local"
	)
	String vapidSubject;

	@Inject
	ObjectMapper objectMapper;

	@jakarta.annotation.PostConstruct
	void initProvider() {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(new BouncyCastleProvider());
		}
	}

	public boolean isConfigured() {
		VapidKeyService.VapidKeys keys = vapidKeyService.loadKeys();
		return keys.publicKey() != null
			&& !keys.publicKey().isBlank()
			&& keys.privateKey() != null
			&& !keys.privateKey()
				.isBlank();
	}

	public boolean send(PushSubscription subscription, String title, String body, String url) {
		if (subscription == null || !isConfigured()) {
			return false;
		}
		try {
			PushService service = buildService();
			ObjectNode payload = objectMapper.createObjectNode();
			payload.put("title", title);
			payload.put("body", body);
			if (url != null) {
				payload.put("url", url);
			}
			byte[] data = objectMapper.writeValueAsBytes(payload);
			Notification notification = new Notification(subscription.endpoint, subscription.p256dh, subscription.auth, data);
			service.send(notification);
			return true;
		}
		catch (Exception ex) {
			LOGGER.warnf(ex, "Failed to send push notification");
			return false;
		}
	}

	public String publicKey() {
		return vapidKeyService.loadKeys().publicKey();
	}

	private PushService buildService() throws GeneralSecurityException {
		VapidKeyService.VapidKeys keys = vapidKeyService.loadKeys();
		PushService service = new PushService();
		service.setSubject(vapidSubject);
		service.setPublicKey(Utils.loadPublicKey(keys.publicKey()));
		service.setPrivateKey(Utils.loadPrivateKey(keys.privateKey()));
		return service;
	}
}
