# LiveLingo v2 — Business & Real-time Translation Architecture

## Goal
Turn LiveLingo from a simple speech translator into a privacy-first translation and meeting intelligence product for consumers and business.

## Open-source references reviewed
- RTranslator: Android, Whisper + local translation, offline-first, Bluetooth/background patterns.
- whisper.cpp Android examples: on-device streaming STT.
- MeetingScribe / meetscribe / ghostmeet / VOA: meeting transcription, speaker diarization, summaries, decisions, action items, local-first processing.

## Product modes
1. Live Translate — continuous speech translation with manual or auto source language.
2. Conversation — two-way translation for in-person dialogue.
3. Meeting — continuous transcript, speaker labels, key topics, decisions, action items, follow-ups.
4. Conference — live captions + translation while listening to a speaker.
5. Notes — record/import audio and generate structured notes after the session.

## Core pipeline
Audio capture -> VAD -> streaming STT -> language confidence -> semantic stabilizer -> incremental translation -> TTS.

### STT strategy
Replace Android SpeechRecognizer as the primary recognizer with a local model pipeline. Target architecture: whisper.cpp/ONNX-compatible Whisper small/base models, selected by device capability. Keep Android SpeechRecognizer only as an optional fallback.

### Semantic stabilizer
Maintain a rolling hypothesis of the sentence and split into:
- stable prefix: safe to translate/speak
- unstable tail: may still change
Release translated speech only when grammatical/semantic confidence is high enough. Never wait for the whole utterance when a stable clause is already known.

### Language handling
- Manual source language overrides auto-detection.
- Auto mode uses multiple observations, script checks and STT confidence before committing.
- Do not switch languages on one short partial result.

## Business intelligence layer
Store a time-coded transcript session and derive structured objects:
- summary
- key points
- decisions
- action items
- owner when mentioned
- deadline when mentioned
- risks/blockers
- open questions
- follow-ups
- topics/tags
- important quotes
- glossary / named entities

## Speaker model
Phase 1: Me / Other using input channel where possible.
Phase 2: speaker diarization for multiple participants.
Phase 3: optional voice profiles for recurring teams, stored locally and opt-in.

## Meeting context
Allow users to add before a meeting:
- agenda
- participant names
- company/product terminology
- documents or notes
Use this context to bias transcription and improve summaries.

## Outputs
- live translated captions
- translated audio
- full transcript with timestamps
- bilingual transcript
- structured meeting brief
- action item checklist
- decisions log
- Markdown/TXT/JSON/PDF export
- future PPTX meeting recap generation

## Privacy model
Default: on-device/local processing where hardware permits. Cloud enhancement should be explicit opt-in and separately labeled.

## Commercial UX
Home screen with mode cards: Translate, Conversation, Meeting, Notes.
Meeting screen emphasizes REC state, elapsed time, detected language, live captions, key-points stream and participant labels.
After session: polished recap with Summary / Decisions / Tasks / Transcript tabs.

## Immediate implementation order
1. Decouple STT behind SpeechEngine interface.
2. Add session transcript store and timestamps.
3. Add semantic stabilizer interface.
4. Add Business Session UI and local key-point extraction.
5. Integrate Whisper engine.
6. Add diarization and structured summarization.
