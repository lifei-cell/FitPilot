package com.fitpilot.infrastructure.events;

import com.fitpilot.notification.infrastructure.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonalRecordEventHandler {
    static final String NOTIFICATION_CONSUMER = "fitpilot-pr-notification-v1";
    private final EventEnvelopeReader reader;
    private final ProcessedEventRepository processedEvents;
    private final NotificationRepository notifications;

    public PersonalRecordEventHandler(EventEnvelopeReader reader, ProcessedEventRepository processedEvents,
                                      NotificationRepository notifications) {
        this.reader = reader;
        this.processedEvents = processedEvents;
        this.notifications = notifications;
    }

    @Transactional
    public void notifyUser(String raw) {
        var event = reader.read(raw, EventTypes.PERSONAL_RECORD_CREATED,
                EventPayloads.PersonalRecordCreated.class);
        if (!processedEvents.claim(event.envelope().eventId(), NOTIFICATION_CONSUMER)) return;
        notifications.insert(event.envelope().eventId(), event.payload());
    }
}
