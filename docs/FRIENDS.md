# Walk with friends: "Maurice is going for a walk. Join them!"

Ultimate+ can tell friends when you start a walk, so they can walk with you from anywhere. It uses a chat your friends are already in,
so there is nothing to host and no account or card to set up. Everything is opt-in.

## How it works
1. Your watch tells the phone you have started walking (the same signal that offers "Record this walk").
2. After you have walked steadily for the time you chose, Ultimate checks every rule you set (switched on, not private, outside quiet
   hours, someone chosen who has not been told recently) and either asks you or posts a short message.
3. The message goes to the chat you picked: "🚶 Maurice is going for a walk. Join them!". Friends get Discord's or Telegram's own
   notification.

## What is sent
Only your name and "walk" or "run" (and, if you leave "Say when I finish" on, how many minutes). Never where you are, how far or your
heart rate. Names are cleaned first: no pings (@everyone, @here), links, formatting or control characters; Discord messages are sent
with mentions switched off.

## Setting up Discord (about a minute)
Server channel > Edit channel > Integrations > Webhooks > New Webhook > Copy Webhook URL. In Ultimate: Walk with friends > Add a chat >
Discord > paste > Add > Send a test. The link can only post to that one channel; delete the webhook in Discord to revoke it. "Share
link with friends" lets other Ultimate users announce to the same channel (keep the link private).

## Telegram and ntfy
Telegram: make a bot with @BotFather, add it to your group, paste the bot token and the group's chat id. The token is stronger than a
webhook, so use a bot made only for this. ntfy: choose a private topic name and paste `https://ntfy.sh/<topic>`; friends subscribe in
the ntfy app.

## Where things are kept
Links and tokens are sealed with a key in the phone's hardware-backed Keystore (`social/FriendsStore`). The rules for when to announce
are `social/WalkAnnouncer`, what may leave the phone is `social/WalkPrivacy`, and building and checking each request is
`social/Delivery`; all are pure and tested (`FriendsCoreTest`, `DeliveryTest`).

## Later: live joining
`firebase/` holds an optional design for a shared live view of a walk (Firestore rules and a notification function, with tests for its
decision rules). It is not used by the app yet and needs a Firebase project on the pay-as-you-go plan; chat posts cover the main idea
without it.
