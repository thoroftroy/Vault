# Vault changelog

## v0.8.1

- The moving background holds still while Battery Saver is on, so it uses next to no power then. It still changes with what the app is doing

## v0.8.0

- Vault offers to fill far less often: only on a sign-in with a password box, on an email or user name box when a login is saved for that app or site, and on address or card forms when you saved details or a card. Search, message and other boxes are left alone, and it can tell which sites have a saved login even while locked (from scrambled fingerprints, never the names)
- New quick look: unlock and copy a user name, password or 2FA code from a small sheet without opening Vault. Opened from the Launcher button's Passwords shortcut, it puts the logins for the app you are in first and can remember a login for that app
- The background safe dial clicks round with each character of your master password, spins back on a wrong one and swings open when Vault unlocks
- The background answers your finger: a tap sends rings out and pops what it lands on, and dragging or scrolling leaves a wake and stirs the scene. Saving, adding, deleting and finishing things send a wave of colour through it, and an error gives a red shudder
- Vault's pop-up keeps out of places it was never needed: search boxes (such as searching contacts), message boxes and notes are never offered anything, and contacts and dialler apps are never offered My details
- The animated background now has a picture of its own behind everything: a turning safe dial. It is faint, so text stays easy to read
- Aqua changes far more through the day: a rose dawn, gold sunrise, turquoise morning, deep azure noon, warm afternoon, orange sunset, violet dusk and a real dark night with the moon shimmering through the water and glowing plankton. Now and then a cloud passes over and the light dims
- Super's space changes with the time of day too: a dark night with brighter stars, a rose dawn, teal morning, cobalt noon and a red and amber sunset
- Opening the app no longer sprays a burst of fast symbols across the background; celebrations are a gentle lift of a few symbols, and only for something you just did

## v0.7.0

- While Vault is locked the background sleeps, grey and almost still; a wrong password flushes it red, and unlocking wakes it with a shower of keys
- Password health sets the mood: calm green when everything is strong, amber with room to improve, a worried red when it needs attention

## v0.6.28

- The symbols drifting in the background are smaller and fainter so they no longer crowd text; selected segments and chips are readable in every look (including Super); the warning banner at the top has a solid backing so its text is easy to read

## v0.6.27

- The animated background is now drawn directly on the screen at full sharpness instead of a small picture stretched up, so it is smooth and no longer looks pixelated; the grain over it is much subtler, and long tab names (like Feedback) shrink to fit instead of being cut off

## v0.6.26

- The password strength meter no longer says Very weak before you have typed anything
- At night the animated background gets a stronger wash, so text stays easy to read on dark scenes

## v0.6.25

- Each app's animated scene now has its own drifting symbols (maths signs in Abacus, notes in Echo, arrows in Wayfinder and so on), and the scene is a little clearer while text stays easy to read
- The message after Android resets fingerprint unlock is now a calm hint instead of a red error
- Animated backgrounds reworked after your report: they are drawn at a higher resolution and smoothly at 24 frames a second without slowing the app down, and no longer show horizontal lines

## v0.6.24

- Every app window now has the animated scene behind it, in the app's own colour (for example orange water in Abacus, green in Pulse, purple in Scribble), following your Aqua, Bliss or Super look and the real sunrise and sunset. Motion Reduced shows a still picture and Off turns it off
- Help buttons (?) explain the more confusing settings

## v0.6.23

- Fixed a gap above the keyboard in screens with a bottom tab bar: the tab bar now steps aside while you type, so you can see what you are writing

## v0.6.22

- Polaris can ask Vault for a login and offer to save one: the same small sheet that unlocks Vault and lists only the logins for the site you are on (needs Polaris 0.6.0)

## v0.6.21

- When a site and username you already have is saved with a different password, Vault now asks: Update (the old password stays in that login's history) or Keep both as two logins. Saving a login that is already stored says so instead of asking
- In the autofill sheet, logins with the same username are told apart by the date they were saved

## v0.6.20

- Autofill no longer opens the whole Vault. A small sheet slides up over the page or app: unlock with your fingerprint or password and, if only one login fits the site, it fills straight away with no list to pick from
- It shows just the logins for that site or app, with a search for anything else. Logins saved with a name but no web address (like "Google") now match their site too
- New My details for forms in Settings: save your name, address, phone, company and card once, and Vault offers to fill them on forms that ask

## v0.6.19

- Fixed autofill suggestions never appearing: the little Vault popup under a username or password box could not be shown on newer Android. It now shows in every browser and app, with your matching logins (or Unlock Vault)

## v0.6.18

- Importing passwords from Google Password Manager, Chrome or any other export no longer locks the vault: picking the file, opening a browser to export, and Android settings screens now leave Vault unlocked while you are away, so the import goes through

## v0.6.17

- Unlocking now says what it is working on while it checks the password

## v0.6.16

- Settings are grouped under clear headings: privacy, master password and new logins

## v0.6.15

- New settings: the username or email filled in for new logins

## v0.6.14

- New setting: hide a shown password again after 5, 15 or 30 seconds, or never

## v0.6.13

- New settings: how long copied passwords stay on the clipboard, and a switch to block screenshots and screen recording
- Text size and accent colour chosen in Control Room now apply here
- New Rotate with the phone switch in Settings

## v0.6.12

- Loading screens now say what they are doing instead of showing a bare spinner
- Wording tidied so no sentence needs a dash

## v0.6.11

- New Codes tab: Vault is an authenticator now. Every two-factor code in one list, refreshing by themselves with a countdown; tap one to copy it. Works with any website that offers an authenticator app
- Add a code by scanning its QR code with the camera, by picking a screenshot of the QR, or by typing the setup key or pasting an otpauth:// link; save it as a new login or add it to one you already have
- Imports Google Authenticator exports (the Transfer accounts QR), so all your codes move over in one scan

## v0.6.10

- Import guides tell the truth: Firefox and Edge for Android can't export passwords, so their guides walk you through exporting on a computer instead; Samsung only exports an encrypted .spass file, and the guide says so
- Every browser is listed in Import and export, not just the ones on this phone
- Long guides scroll instead of being cut off
- Titles shrink to fit instead of being cut off

## v0.6.9

- Leaked-password check on the Health tab: finds passwords that appear in known data breaches (Have I Been Pwned). Only the first 5 characters of a scrambled copy of each password leave your phone, and only when you tap Check
- Fixed a crash when moving around with arrow keys or Tab while a screen was closing

## v0.6.8

- Lighter on the battery: in the Super look the drifting sparkles settle after a few seconds without a touch (the next tap wakes them), so a screen you're reading no longer redraws 25 times a second

## v0.6.7

- Changing the look in Control Room now restyles this app's widgets straight away, with no refresh or restart

## v0.6.6

- Importing from Firefox, Chrome, Google and other browsers is easy to find: an Import button on the Passwords screen, on the empty vault, and in the menu. Tap a browser for a step-by-step guide that opens it, tells you exactly what to tap, then picks up the file
- Other Wonderdesk apps can keep their passwords in Vault: the Wayfinder Safe folder asks Vault for its password (unlocked with your fingerprint or master password) and can save it there when you create it

## v0.6.5

- Polish and fixes

## v0.6.4

- Fixes from a full round of stress testing on every screen

## v0.6.3

- Tap the tab you're already on to glide back to the top of its list
- Nothing required stays hidden: if Android is blocking something the app needs, a slim banner says what and takes you straight to the switch, and Settings › Permissions and access lists everything with its state

## v0.6.2

- Logins glide into place when you add or remove them
- Smoother everywhere: new screens, tabs and Settings ease in instead of snapping, and buttons, tabs, chips and switches give a light tap you can feel (following your phone's touch-feedback setting)
- Android's predictive back: the back gesture shows a live preview of where you're going
- Super look: tap sparkles now match Bliss Player: soft glowing sparks with trails, drifting rainbow colours and a faint aurora, instead of big solid circles

## v0.6.1

- Much faster and smoother: the app is now built optimised (the way Play Store apps are), so screens open quicker, scrolling is fluid and the APK is about a quarter of the size
- Screens and lists no longer decode pictures on the main thread, and skip redrawing parts that didn't change

## v0.6.0

- Polish and stability fixes from the suite-wide test pass
- Works properly in landscape: tabs move to a rail at the side so the content keeps its height, and screens that ran out of room now scroll
- If the app ever crashes it says so next time it opens and offers to send a report (only the error, app version and phone model, with file names and links removed). Turn on automatic sending in Control Room › Wonderdesk
- Lists can no longer crash when two items share a name

## v0.5.0

- Unlock with your fingerprint: after unlocking with your master password, Vault offers to turn it on, and Settings links to fingerprint setup
- If the app ever crashes or freezes, a report goes to Control Room so it can be shared and fixed
- App icon art is larger and fills its tile without being cut off

## v0.4.1

- Stronger strength meter: a password only counts as Strong when it mixes capitals, lowercase, numbers and symbols and has at least 12 characters, and Vault tells you what's missing
- Generated passphrases always include a capital, a number and a symbol
- New Browser and Google passwords: step-by-step export from Chrome, Firefox, Google Password Manager, Samsung Internet, Edge and Brave, then import the file. Works with Bitwarden, 1Password and LastPass exports too
- Duplicates merge automatically (changed passwords keep the old one in history), and Vault offers to delete the plain-text export afterwards
- Export Vault's passwords as a CSV that Chrome, Google and Firefox can import
- Two-factor codes only tick while you're looking at them
- App icon redrawn so nothing is cut off: round icons fill the space, the rest sit on an Aqua tile
- Smoother and lighter on the battery: the window background no longer animates, tap effects sleep until you tap, and nothing ticks while the app is in the background
- Opening the app no longer waits on Control Room to read the suite look

## v0.4.0

- Fingerprint unlock and autofill for Firefox and other apps
- Password and passphrase generator, two-factor codes, password health
- Recovery code and password history; the file stays compatible with Vault on Linux
