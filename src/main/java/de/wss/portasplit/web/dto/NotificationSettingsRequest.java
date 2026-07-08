package de.wss.portasplit.web.dto;

/** Update for the notification settings. A {@code null} field is left unchanged. */
public record NotificationSettingsRequest(
        Boolean telegramNotify,
        Boolean notifyCallOnly
) {
}
