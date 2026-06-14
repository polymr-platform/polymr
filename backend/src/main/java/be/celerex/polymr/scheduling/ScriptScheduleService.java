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

package be.celerex.polymr.scheduling;

import be.celerex.polymr.model.Script;
import be.celerex.polymr.model.ScriptType;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.ZoneId;
import java.util.TimeZone;
import org.dmfs.rfc5545.DateTime;
import org.dmfs.rfc5545.recur.RecurrenceRule;
import org.dmfs.rfc5545.recur.RecurrenceRuleIterator;

@ApplicationScoped
public class ScriptScheduleService {
	public Instant resolveNextRun(Script script, Instant after) {
		if (script == null || !script.scheduled || script.type != ScriptType.STANDALONE) {
			return null;
		}
		String rrule = script.scheduleRrule;
		String timezone = script.scheduleTimezone;
		if (rrule == null || rrule.isBlank() || timezone == null || timezone.isBlank()) {
			return null;
		}
		Instant startAt = script.scheduleStartAt != null ? script.scheduleStartAt : after;
		Instant endAt = script.scheduleEndAt;
		Instant anchor = after == null ? Instant.now() : after;
		ZoneId zone;
		try {
			zone = ZoneId.of(timezone.trim());
		}
		catch (Exception ex) {
			return null;
		}
		TimeZone tz = TimeZone.getTimeZone(zone);
		DateTime start = new DateTime(tz, startAt.toEpochMilli());
		RecurrenceRule rule;
		String normalized = rrule.trim();
		if (normalized.toUpperCase().startsWith("RRULE:")) {
			normalized = normalized.substring("RRULE:".length());
		}
		try {
			rule = new RecurrenceRule(normalized);
		}
		catch (Exception ex) {
			return null;
		}
		RecurrenceRuleIterator iterator = rule.iterator(start);
		long anchorMillis = anchor.toEpochMilli();
		while (iterator.hasNext()) {
			DateTime next = iterator.nextDateTime();
			long nextMillis = next.getTimestamp();
			if (nextMillis <= anchorMillis) {
				continue;
			}
			Instant nextInstant = Instant.ofEpochMilli(nextMillis);
			if (endAt != null && nextInstant.isAfter(endAt)) {
				return null;
			}
			return nextInstant;
		}
		return null;
	}
}
