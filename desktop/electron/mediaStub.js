/**
 * Non-Windows media session stub (idle).
 */
export async function getCurrentMediaSession() {
  return {
    state: 'idle',
    title: null,
    artist: null,
    appName: null,
    appPackage: null,
    positionMs: 0,
    durationMs: 0,
  };
}

export function startMediaWatcher(_onTick) {
  return () => {};
}
