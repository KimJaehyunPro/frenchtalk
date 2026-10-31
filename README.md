# FrenchTalk — real-time spoken French practice (demo)

A personal Android app: you talk, a French "person" talks back in a natural human
voice, in real time, in a scene you pick (café, boulangerie, gare, médecin,
DELF B2 debate…). You can interrupt it mid-sentence like a real conversation,
and a live transcript of both sides appears on screen.

## How it works
- Voice AI: **Google Gemini Live API**, speech-to-speech (no robotic text-to-speech
  step), default model `gemini-3.8-live`. The model hears your audio directly and
  answers with native audio, so intonation and fillers sound human.
- Cost: the Gemini API **free tier** covers this model (rate-limited). If you
  ever switch to the paid tier it's roughly $0.005/min in + $0.018/min out
  (≈ $1.40 per hour of talking). Note: on the free tier Google may use your
  content to improve its products.
- No server, no account system: the app connects straight from your phone to Google
  with your own API key (fine for personal use; don't publish an APK with your key).
- Plain Java + Android SDK + OkHttp. No other libraries.

## Setup (≈15 minutes, all free)
1. **API key:** go to https://aistudio.google.com/apikey → "Create API key". Copy it.
2. **Android Studio:** install it (https://developer.android.com/studio).
   *File → Open* → select this `FrenchTalk` folder. Let Gradle sync (first time
   downloads the SDK — takes a few minutes).
   - If Studio offers to upgrade the Android Gradle Plugin, accepting is fine.
   - If sync complains about the Java version, set *Settings → Build Tools →
     Gradle → Gradle JDK* to a JDK 17 or 21 (the bundled "jbr-21" works).
3. **Phone:** Settings → About phone → tap "Build number" 7× → Developer options →
   enable **USB debugging** (or Wireless debugging). Plug in, accept the prompt.
4. Press **Run ▶** in Android Studio. The app installs and opens.
5. In the app: open **Réglages**, paste the API key, pick a situation / level / mode,
   press **Parler**, allow the microphone. The character speaks first.

To get an installable APK instead: *Build → Generate App Bundles or APKs → Build APK(s)*,
then copy `app/build/outputs/apk/debug/app-debug.apk` to the phone.

## Using it
- **Niveau** A2 → C1 changes vocabulary, speed and slang. B2 pushes you to argue
  and justify (DELF B2 style).
- **Mode Immersion:** no explicit corrections; the character naturally rephrases
  your mistakes correctly in its reply (how natives actually correct).
  **Mode Coach:** one short correction at the start of its reply, then back in character.
- **Earphones strongly recommended** — then you can interrupt freely. On the
  loudspeaker, Android's echo canceller is used, which works on most phones.
- Type in the bottom field to send a word you can't pronounce or say "comment on dit…".
- **Copier** copies the transcript so you can review vocabulary afterwards.

## If something fails
The status line shows the server's reason.
- *Key refused* → re-copy the key.
- *Quota / 429* → free-tier limit; wait a bit.
- *Model not found* → model names change over time. In Réglages, try
  `gemini-3.1-flash-live-preview`, or check the current list at
  https://ai.google.dev/gemini-api/docs/live-api .
- A voice name rejected → set Voix to `default`.

## Files
- `MainActivity.java` — UI, transcript, session start/stop
- `LiveSession.java` — Gemini Live WebSocket protocol
- `AudioIO.java` — mic capture (16 kHz) and streaming playback (24 kHz), echo cancellation
- `Prompts.java` — scenes, levels, and the "sound like a real French person" system prompt
