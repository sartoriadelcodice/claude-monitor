-- Language of the push notifications, chosen per device: the dashboard sends it with the subscription
-- and updates it when the interface language changes.
ALTER TABLE push_subscriptions ADD COLUMN language VARCHAR(10) NOT NULL DEFAULT 'en';
