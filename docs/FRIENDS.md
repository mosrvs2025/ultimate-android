# Friends: "Maurice is going for a walk. Join them?"

Ultimate+ can tell chosen friends when you start a walk, so they can walk with you from anywhere. Everything is opt-in; nothing is
announced until you turn it on and pick who.

## What goes where
- **On your phone** (`social/`): the friend code, the rules for *when* to announce (`WalkAnnouncer`), and what a friend is allowed to
  see (`WalkPrivacy`). Position never leaves the phone unless you switch on "where" for friends, and then only rounded to ~100 m.
- **In your Firebase project** (`firebase/`): who is friends with whom (`pairs`), your live walk (`walks`), and a small function that
  sends the push notifications. The rules in `firestore.rules` decide who can read what; the function enforces a minimum of one
  announcement per friend per hour whatever a phone asks for.

## One-time setup (about 20 minutes, on a computer)
1. Go to https://console.firebase.google.com, **Add project** (any name, e.g. `ultimate-friends`). Google Analytics: off.
2. **Build > Authentication > Get started > Sign-in method > Anonymous > Enable.** (Each phone gets a private ID; no email needed.)
3. **Build > Firestore Database > Create database**, production mode, a region near you.
4. **Upgrade the project to the Blaze (pay-as-you-go) plan.** This needs a card, and is required only because Google runs the
   notification function on that plan. At friend-group size the free allowance covers it, so the bill is normally $0; add a budget
   alert (Billing > Budgets) for peace of mind. Without Blaze, friends still see your walk live when they open the app, but no
   push notification is sent.
5. **Project settings > General > Your apps > Add app > Android**, package `app.ultimate.plus`. Skip downloading the config file;
   copy the values from **Project settings > General** (Project ID, Web API key) and **Your apps** (App ID) and **Cloud Messaging**
   (Sender ID). The app asks for these once (they identify the project; they are not passwords).
6. Deploy the rules and the function (needs Node 20+ and `npm i -g firebase-tools`):
   ```
   cd firebase
   firebase login
   firebase use --add          # pick your project
   (cd functions && npm install)
   firebase deploy --only firestore,functions
   ```

## Using it
Friends > "My code" shows your code (like `K7M2-X9QD-F`). Type a friend's code to ask them; when they accept, choose whether you tell
them when you walk, and what they may see (steps, distance, pace, heart, neighbourhood). "Go private" pauses everything for a set
time. You can mute anyone without telling them.

## Tests
`npm test` in `firebase/functions` (decision rules for who is told). `FriendsCoreTest` on the phone (codes, privacy, announcing).
The Firestore rules are written to the same rules but have not been run against the Firebase emulator here.
