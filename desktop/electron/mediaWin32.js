/**
 * Windows SMTC reader via PowerShell + GlobalSystemMediaTransportControlsSessionManager.
 * Polls on an interval; event-driven WinRT hooks are preferred later via a native addon.
 */
import { execFile } from 'child_process';
import { promisify } from 'util';

const execFileAsync = promisify(execFile);

const PS_SCRIPT = `
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation\`1'
})[0]
Function Await($WinRtTask, $ResultType) {
  $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
  $netTask = $asTask.Invoke($null, @($WinRtTask))
  $netTask.Wait(-1) | Out-Null
  $netTask.Result
}
try {
  [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
  $mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
  $session = $mgr.GetCurrentSession()
  if ($null -eq $session) {
    '{"state":"idle"}'
    return
  }
  $info = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
  $playback = $session.GetPlaybackInfo()
  $timeline = $session.GetTimelineProperties()
  $status = [string]$playback.PlaybackStatus
  $state = if ($status -eq 'Playing') { 'playing' } elseif ($status -eq 'Paused') { 'paused' } else { 'idle' }
  $appId = ''
  try { $appId = [string]$session.SourceAppUserModelId } catch {}
  $obj = [ordered]@{
    state = $state
    title = [string]$info.Title
    artist = [string]$info.Artist
    appName = $appId
    appPackage = $appId
    positionMs = [int64]($timeline.Position.TotalMilliseconds)
    durationMs = [int64](($timeline.EndTime - $timeline.StartTime).TotalMilliseconds)
  }
  $obj | ConvertTo-Json -Compress
} catch {
  '{"state":"idle","error":"smtc_unavailable"}'
}
`.trim();

let lastErrorLogged = false;

export async function getCurrentMediaSession() {
  try {
    const { stdout } = await execFileAsync(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', PS_SCRIPT],
      { windowsHide: true, timeout: 8000, maxBuffer: 1024 * 256 }
    );
    const text = (stdout || '').trim();
    if (!text) return { state: 'idle' };
    const json = JSON.parse(text);
    return {
      state: json.state || 'idle',
      title: json.title || null,
      artist: json.artist || null,
      appName: json.appName || json.appPackage || null,
      appPackage: json.appPackage || json.appName || null,
      positionMs: Number(json.positionMs) || 0,
      durationMs: Number(json.durationMs) || 0,
    };
  } catch (e) {
    if (!lastErrorLogged) {
      console.warn('[mediaWin32] SMTC read failed:', e.message);
      lastErrorLogged = true;
    }
    return { state: 'idle' };
  }
}

/**
 * Poll SMTC every intervalMs. Calls onTick(session, elapsedMsSinceLast) when playing.
 * Returns a stop function.
 */
export function startMediaWatcher(onTick, intervalMs = 5000) {
  let stopped = false;
  let last = null;
  let lastAt = Date.now();

  async function loop() {
    while (!stopped) {
      const session = await getCurrentMediaSession();
      const now = Date.now();
      const elapsed = now - lastAt;
      lastAt = now;
      if (session.state === 'playing') {
        const same =
          last &&
          last.title === session.title &&
          last.appPackage === session.appPackage;
        onTick(session, same ? elapsed : Math.min(elapsed, intervalMs));
      } else {
        // Pause / idle must clear nowPlaying in the main process.
        onTick(session, 0);
      }
      last = session;
      await new Promise((r) => setTimeout(r, intervalMs));
    }
  }
  loop();
  return () => {
    stopped = true;
  };
}
