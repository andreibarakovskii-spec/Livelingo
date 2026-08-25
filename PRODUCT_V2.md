# LiveLingo v2 Business

LiveLingo v2 combines real-time multilingual conversation translation with meeting intelligence.

## Consumer modes
- Live Translate: continuous speech translation with manual or automatic source language.
- Conversation: two-way translation for face-to-face conversations.
- Earbuds: microphone input and translated TTS routed to Bluetooth audio.

## Business modes
- Meeting: continuous transcript with original and translated text.
- Conference: long-form listening with key points surfaced live.
- Notes: process an existing meeting transcript into structured outcomes.

## Meeting intelligence
Each meeting maintains a timeline of utterances and derived items:
- decisions
- action items
- risks
- open questions
- follow-ups
- important facts

## Planned architecture
Audio -> streaming STT -> language resolution -> semantic stabilizer -> translation -> TTS
                                  -> meeting transcript -> business intelligence

STT should move away from device-specific Android SpeechRecognizer toward an app-controlled local or hybrid engine based on proven open-source Whisper-style pipelines. Translation remains behind an abstraction so local and cloud engines can be swapped independently.

## Commercial constraints
- Offline-first whenever practical.
- User must be able to choose a fixed input language for reliability.
- No silent cloud upload of meeting audio.
- Meeting recordings/transcripts require explicit recording state in the UI.
- Models and third-party code must have licenses compatible with the intended commercial distribution.
