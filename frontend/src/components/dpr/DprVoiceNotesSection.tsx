"use client";

import { useEffect, useRef, useState } from "react";
import { AudioLines, Lock, Mic, Paperclip, Square, Trash2, X } from "lucide-react";
import { dprApi } from "@/lib/api/dprApi";
import type { DprVoiceNote } from "@/lib/types/dpr";

const MAX_FILE_BYTES = 25 * 1024 * 1024;
const ACCEPTED_MIME = [
  "audio/webm",
  "audio/ogg",
  "audio/mp4",
  "audio/mpeg",
  "audio/aac",
  "audio/wav",
  "audio/x-wav",
  "audio/x-m4a",
];

export interface PendingVoiceNote {
  blob: Blob;
  fileName: string;
  caption: string;
  durationSeconds: number | null;
  previewUrl: string;
}

interface Props {
  projectId: string;
  /** When null we're in "new DPR" mode — only pendingVoiceNotes are shown; uploads happen post-save. */
  dprId: string | null;
  pending: PendingVoiceNote[];
  existing: DprVoiceNote[];
  onPendingChange: (next: PendingVoiceNote[]) => void;
  onExistingChange: (next: DprVoiceNote[]) => void;
}

/** Derive a sensible filename + extension from a recorded blob's MIME type. */
function fileNameForMime(mime: string): string {
  const ts = Date.now();
  const ext = mime.includes("mp4") || mime.includes("m4a")
    ? "mp4"
    : mime.includes("ogg")
      ? "ogg"
      : mime.includes("mpeg")
        ? "mp3"
        : mime.includes("wav")
          ? "wav"
          : "webm";
  return `voice-note-${ts}.${ext}`;
}

/**
 * Voice notes section for the DPR Add/Edit drawer. Persisted audio attachments — distinct from the
 * top-bar "Voice fill" assistant (which transcribes speech and discards the audio). Two capture
 * paths: record via {@code MediaRecorder} (same lifecycle as {@code DprVoiceAssistant}) or attach an
 * existing audio file. Two-column behaviour mirrors {@code DprPhotosSection}:
 *  - Existing notes (edit mode) load the audio through an authenticated fetch + blob URL, since the
 *    stream endpoint is JWT-protected and {@code <audio src>} cannot send headers.
 *  - Pending notes (captured before save) live entirely client-side until the parent flushes them
 *    via {@code dprApi.uploadVoiceNotes} after the DPR row is saved.
 */
export function DprVoiceNotesSection({
  projectId,
  dprId,
  pending,
  existing,
  onPendingChange,
  onExistingChange,
}: Props) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyDeletes, setBusyDeletes] = useState<Set<string>>(new Set());

  const [isRecording, setIsRecording] = useState(false);
  const [permissionDenied, setPermissionDenied] = useState(false);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const recordStartRef = useRef<number>(0);
  // Latest pending list, so the recorder's async onstop appends to current state, not a stale
  // closure captured when recording began.
  const pendingRef = useRef(pending);
  useEffect(() => {
    pendingRef.current = pending;
  }, [pending]);

  // Pending preview URLs are owned by this component — revoke when removed or on unmount so the
  // browser can release the underlying ArrayBuffer. Also stop the mic if we unmount mid-recording.
  useEffect(() => {
    return () => {
      pendingRef.current.forEach((p) => URL.revokeObjectURL(p.previewUrl));
      streamRef.current?.getTracks().forEach((t) => t.stop());
    };
  }, []);

  const handleSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    e.target.value = ""; // allow re-selecting the same file after a remove

    const accepted: PendingVoiceNote[] = [];
    const rejected: string[] = [];
    for (const file of files) {
      if (!ACCEPTED_MIME.includes(file.type.toLowerCase())) {
        rejected.push(`${file.name}: unsupported type`);
        continue;
      }
      if (file.size > MAX_FILE_BYTES) {
        rejected.push(`${file.name}: exceeds 25 MB`);
        continue;
      }
      accepted.push({
        blob: file,
        fileName: file.name,
        caption: "",
        durationSeconds: null,
        previewUrl: URL.createObjectURL(file),
      });
    }
    if (accepted.length > 0) onPendingChange([...pending, ...accepted]);
    setError(rejected.length > 0 ? rejected.join("; ") : null);
  };

  const startRecording = async () => {
    if (isRecording) return; // guard against overlapping recordings
    setError(null);
    setPermissionDenied(false);
    // Microphone capture only works in a secure context: getUserMedia is available on https or on
    // localhost. On a plain-http production origin navigator.mediaDevices is undefined and the
    // browser will never prompt — surface that clearly instead of a cryptic failure.
    if (typeof window === "undefined" || !window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
      setError(
        "Recording needs a secure connection (HTTPS) — or localhost in development. " +
          'On an insecure (http) site the browser disables the microphone; use "Attach audio" instead, ' +
          "or open the app over https.",
      );
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      const mimeType = MediaRecorder.isTypeSupported("audio/webm")
        ? "audio/webm"
        : MediaRecorder.isTypeSupported("audio/mp4")
          ? "audio/mp4"
          : "";
      const recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);
      mediaRecorderRef.current = recorder;

      const chunks: BlobPart[] = [];
      recorder.ondataavailable = (e) => {
        if (e.data.size > 0) chunks.push(e.data);
      };
      recorder.onstop = () => {
        const type = recorder.mimeType || "audio/webm";
        const blob = new Blob(chunks, { type });
        stream.getTracks().forEach((t) => t.stop());
        streamRef.current = null;
        const elapsedMs = Date.now() - recordStartRef.current;
        const durationSeconds = elapsedMs > 0 ? Math.round(elapsedMs / 1000) : null;
        const note: PendingVoiceNote = {
          blob,
          fileName: fileNameForMime(type),
          caption: "",
          durationSeconds,
          previewUrl: URL.createObjectURL(blob),
        };
        onPendingChange([...pendingRef.current, note]);
      };
      recordStartRef.current = Date.now();
      recorder.start();
      setIsRecording(true);
    } catch (err: unknown) {
      const name = err instanceof Error ? err.name : "";
      if (name === "NotAllowedError" || name === "SecurityError") {
        setPermissionDenied(true);
      } else if (name === "NotFoundError" || name === "OverconstrainedError") {
        setError("No microphone found on this device.");
      } else if (name === "NotReadableError") {
        setError("Microphone is in use by another application.");
      } else {
        setError("Could not start the microphone.");
      }
    }
  };

  const stopRecording = () => {
    if (mediaRecorderRef.current && mediaRecorderRef.current.state !== "inactive") {
      mediaRecorderRef.current.stop();
    }
    setIsRecording(false);
  };

  const removePending = (idx: number) => {
    const next = pending.slice();
    const [removed] = next.splice(idx, 1);
    if (removed) URL.revokeObjectURL(removed.previewUrl);
    onPendingChange(next);
  };

  const setPendingCaption = (idx: number, caption: string) => {
    const next = pending.slice();
    next[idx] = { ...next[idx], caption };
    onPendingChange(next);
  };

  const deleteExisting = async (voiceNoteId: string) => {
    if (!dprId) return;
    if (!window.confirm("Delete this voice note? This cannot be undone.")) return;
    setBusyDeletes((s) => new Set(s).add(voiceNoteId));
    try {
      await dprApi.deleteVoiceNote(projectId, dprId, voiceNoteId);
      onExistingChange(existing.filter((v) => v.id !== voiceNoteId));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to delete voice note");
    } finally {
      setBusyDeletes((s) => {
        const n = new Set(s);
        n.delete(voiceNoteId);
        return n;
      });
    }
  };

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2 text-sm font-semibold text-charcoal">
          <AudioLines className="h-4 w-4 text-gold" />
          Voice notes
          {(pending.length > 0 || existing.length > 0) && (
            <span className="text-xs font-normal text-slate">
              ({existing.length} saved{pending.length > 0 ? `, ${pending.length} pending` : ""})
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={isRecording ? stopRecording : startRecording}
            className={`inline-flex cursor-pointer items-center gap-1.5 rounded-md border px-3 py-1.5 text-sm font-semibold transition ${
              isRecording
                ? "border-burgundy bg-burgundy/10 text-burgundy animate-pulse"
                : "border-hairline bg-paper text-charcoal hover:bg-ivory"
            }`}
          >
            {isRecording ? <Square className="h-4 w-4" /> : <Mic className="h-4 w-4" />}
            {isRecording ? "Stop" : "Record"}
          </button>
          <button
            type="button"
            onClick={() => inputRef.current?.click()}
            className="inline-flex cursor-pointer items-center gap-1.5 rounded-md border border-hairline bg-paper px-3 py-1.5 text-sm font-semibold text-charcoal hover:bg-ivory"
          >
            <Paperclip className="h-4 w-4" />
            Attach audio
          </button>
          <input
            ref={inputRef}
            type="file"
            multiple
            accept="audio/*"
            hidden
            onChange={handleSelect}
          />
        </div>
      </div>

      <p className="text-xs text-slate">
        Attach spoken notes to this report. Unlike <span className="font-semibold">Voice fill</span>,
        these recordings are saved and can be played back later.
      </p>

      {permissionDenied && (
        <div className="space-y-1 rounded border border-burgundy/30 bg-burgundy/10 px-3 py-2 text-xs text-burgundy">
          <div className="flex items-center gap-1.5 font-semibold uppercase tracking-wide">
            <Lock className="h-3.5 w-3.5" />
            Microphone blocked
          </div>
          <div className="text-charcoal">
            Allow microphone access for this site — click the lock / ⓘ icon in the address bar and
            set <span className="font-semibold">Microphone</span> to Allow, then reload. If it is
            already allowed, also check your operating-system microphone privacy (e.g. Windows →
            Privacy &amp; security → Microphone → let desktop apps access your microphone), then
            click Record again. You can also use <span className="font-semibold">Attach audio</span>{" "}
            to add an existing file instead.
          </div>
        </div>
      )}

      {error && <div className="text-xs text-burgundy">{error}</div>}

      {existing.length === 0 && pending.length === 0 && (
        <div className="rounded-md border border-dashed border-hairline px-3 py-4 text-center text-xs text-slate">
          {dprId
            ? "No voice notes yet. Use Record or Attach audio to add one."
            : "Record or attach a note now — it'll upload after you save the DPR."}
        </div>
      )}

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {existing.map((note) => (
          <ExistingVoiceNoteTile
            key={note.id}
            projectId={projectId}
            dprId={dprId!}
            note={note}
            busy={busyDeletes.has(note.id)}
            onDelete={() => deleteExisting(note.id)}
          />
        ))}
        {pending.map((p, idx) => (
          <div
            key={`pending-${idx}-${p.fileName}`}
            className="overflow-hidden rounded-md border border-dashed border-gold/50 bg-paper"
          >
            <div className="flex items-center justify-between gap-2 border-b border-hairline px-2 py-1.5">
              <div className="flex min-w-0 items-center gap-1.5 text-xs text-charcoal">
                <Mic className="h-3.5 w-3.5 shrink-0 text-gold" />
                <span className="truncate" title={p.fileName}>
                  {p.fileName}
                </span>
                {p.durationSeconds != null && (
                  <span className="shrink-0 text-slate">· {p.durationSeconds}s</span>
                )}
              </div>
              <div className="flex shrink-0 items-center gap-1.5">
                <span className="rounded bg-gold px-1.5 py-0.5 text-[10px] font-bold uppercase text-gold-ink">
                  Pending
                </span>
                <button
                  type="button"
                  onClick={() => removePending(idx)}
                  className="rounded-full bg-paper/95 p-1 text-charcoal hover:bg-burgundy hover:text-white"
                  aria-label="Remove voice note"
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
            <VoiceNoteAudio src={p.previewUrl} fileName={p.fileName} />
            <input
              type="text"
              value={p.caption}
              onChange={(e) => setPendingCaption(idx, e.target.value)}
              placeholder="Caption (optional)"
              maxLength={500}
              className="w-full border-0 border-t border-hairline bg-paper px-2 py-1.5 text-xs text-charcoal placeholder:text-slate focus:outline-none focus:ring-0"
            />
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * Audio player with a graceful fallback: some codecs the backend stores and serves perfectly are
 * still undecodable by a given browser (e.g. telephony-codec WAVs like G.711/GSM in Chrome, or
 * webm/Opus in Safari). When the {@code <audio>} element fails to decode, surface a clear message
 * plus a Download link so the user can still retrieve the file and play it in another app.
 */
function VoiceNoteAudio({ src, fileName }: { src: string; fileName: string }) {
  const [playError, setPlayError] = useState(false);
  return (
    <>
      <audio
        controls
        src={src}
        onError={() => setPlayError(true)}
        className="w-full px-2 py-2"
      />
      {playError && (
        <div className="px-2 pb-2 text-center text-xs text-slate">
          Your browser can&apos;t play this audio format.{" "}
          <a href={src} download={fileName} className="font-semibold text-gold underline">
            Download the file
          </a>{" "}
          to play it in another app.
        </div>
      )}
    </>
  );
}

interface TileProps {
  projectId: string;
  dprId: string;
  note: DprVoiceNote;
  busy: boolean;
  onDelete: () => void;
}

function ExistingVoiceNoteTile({ projectId, dprId, note, busy, onDelete }: TileProps) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let createdUrl: string | null = null;
    dprApi
      .fetchVoiceNoteBlobUrl(projectId, dprId, note.id)
      .then((url) => {
        if (cancelled) {
          URL.revokeObjectURL(url);
        } else {
          createdUrl = url;
          setSrc(url);
        }
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [projectId, dprId, note.id]);

  return (
    <div className="overflow-hidden rounded-md border border-hairline bg-paper">
      <div className="flex items-center justify-between gap-2 border-b border-hairline px-2 py-1.5">
        <div className="flex min-w-0 items-center gap-1.5 text-xs text-charcoal">
          <AudioLines className="h-3.5 w-3.5 shrink-0 text-gold" />
          <span className="truncate" title={note.fileName}>
            {note.fileName}
          </span>
          {note.durationSeconds != null && (
            <span className="shrink-0 text-slate">· {note.durationSeconds}s</span>
          )}
        </div>
        <button
          type="button"
          onClick={onDelete}
          disabled={busy}
          className="shrink-0 rounded-full bg-paper/95 p-1 text-charcoal hover:bg-burgundy hover:text-white disabled:opacity-50"
          aria-label="Delete voice note"
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </div>
      {src && !failed ? (
        <VoiceNoteAudio src={src} fileName={note.fileName} />
      ) : (
        <div className="px-2 py-3 text-center text-xs text-slate">
          {failed ? "Failed to load" : "Loading…"}
        </div>
      )}
      <div className="border-t border-hairline px-2 py-1.5 text-xs text-charcoal">
        {note.caption ? (
          <span className="line-clamp-2">{note.caption}</span>
        ) : (
          <span className="text-slate">No caption</span>
        )}
      </div>
    </div>
  );
}
