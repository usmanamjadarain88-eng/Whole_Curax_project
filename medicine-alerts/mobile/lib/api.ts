/**
 * Backend API client. Same base URL as desktop (Railway).
 * When mobile writes here, backend notifies data bus so desktop sees changes.
 */
import { API_BASE_URL } from './constants';

export function getBaseUrl(): string {
  return API_BASE_URL.replace(/\/$/, '');
}

export function generateDeviceId(prefix: string): string {
  const rand = Math.random().toString(36).slice(2, 10);
  return `${prefix}_${rand}`;
}

export function generateDeviceApiKey(): string {
  return `${Math.random().toString(36).slice(2, 10)}${Math.random().toString(36).slice(2, 10)}`;
}

export async function saveCredentials(payload: {
  bot_id: string;
  api_key: string;
  role: 'admin' | 'user';
  access_code?: string;
  admin_id?: string;
  name?: string;
  email?: string;
  fcm_token?: string;
}): Promise<Record<string, unknown> | null> {
  const base = getBaseUrl();
  try {
    const res = await fetch(`${base}/save-credentials`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (!res.ok) return null;
    return (await res.json()) as Record<string, unknown>;
  } catch {
    return null;
  }
}

export async function getAdminData(
  accessCode: string,
  lastSyncTime?: string,
  actAsUserId?: string
): Promise<Record<string, unknown> | null> {
  const base = getBaseUrl();
  const params = new URLSearchParams({ access_code: accessCode });
  if (lastSyncTime) params.set('last_sync_time', lastSyncTime);
  if (actAsUserId?.trim()) params.set('act_as_user_id', actAsUserId.trim());
  try {
    const res = await fetch(`${base}/admin/data?${params.toString()}`, { method: 'GET' });
    if (!res.ok) return null;
    return (await res.json()) as Record<string, unknown>;
  } catch {
    return null;
  }
}

export async function putMedicalReminders(
  accessCode: string,
  medicalReminders: { appointments?: unknown[]; prescriptions?: unknown[]; lab_tests?: unknown[]; custom?: unknown[] },
  actAsUserId?: string
): Promise<boolean> {
  const base = getBaseUrl();
  try {
    const body: Record<string, unknown> = { access_code: accessCode, medical_reminders: medicalReminders };
    if (actAsUserId?.trim()) body.act_as_user_id = actAsUserId.trim();
    const res = await fetch(`${base}/admin/medical_reminders`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!res.ok) return false;
    return true;
  } catch {
    return false;
  }
}

export async function notifyBackend(accessCode: string): Promise<boolean> {
  const base = getBaseUrl();
  try {
    const res = await fetch(`${base}/admin/notify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ access_code: accessCode }),
    });
    return res.ok;
  } catch {
    return false;
  }
}

/** Update admin FCM token when it becomes available (e.g. after permission grant). Backend stores it so push alerts work. */
export async function updateAdminFcmToken(accessCode: string, fcmToken: string): Promise<boolean> {
  const base = getBaseUrl();
  if (!accessCode || !fcmToken || !fcmToken.trim()) return false;
  try {
    const res = await fetch(`${base}/admin/fcm-token`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ access_code: accessCode.trim(), fcm_token: fcmToken.trim() }),
    });
    return res.ok;
  } catch {
    return false;
  }
}

/** GET /admin/connection for Connection panel: fcm_token_set, connected, linked_users. */
export async function getAdminConnection(accessCode: string): Promise<{
  fcm_token_set: boolean;
  connected: boolean;
  linked_users: Array<{ id: string; name?: string; bot_id?: string }>;
} | null> {
  const base = getBaseUrl();
  if (!accessCode?.trim()) return null;
  try {
    const params = new URLSearchParams({ access_code: accessCode.trim() });
    const res = await fetch(`${base}/admin/connection?${params.toString()}`, { method: 'GET' });
    if (!res.ok) return null;
    return (await res.json()) as { fcm_token_set: boolean; connected: boolean; linked_users: Array<{ id: string; name?: string; bot_id?: string }> };
  } catch {
    return null;
  }
}

export async function connectToAdmin(payload: {
  connection_code: string;
  bot_id: string;
  api_key: string;
  name?: string;
  fcm_token?: string;
}): Promise<Record<string, unknown> | null> {
  const base = getBaseUrl();
  try {
    const res = await fetch(`${base}/connect-to-admin`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (!res.ok) return null;
    return (await res.json()) as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** Convert GET /admin/data response to POST /admin/sync payload (medicine_boxes, dose_log, etc.). */
export function buildSyncPayloadFromAdminData(data: Record<string, unknown>): {
  medicine_boxes: Record<string, Record<string, unknown>>;
  dose_log: unknown[];
  alert_settings: Record<string, unknown>;
  gmail_config: Record<string, unknown>;
  medical_reminders: Record<string, unknown[]>;
} {
  const medicines = (data.medicines as Record<string, unknown>[]) || [];
  const dose_logs = (data.dose_logs as Record<string, unknown>[]) || [];
  const blob = (data.alert_settings as Record<string, unknown>) || {};
  const medical_reminders = (data.medical_reminders as Record<string, unknown[]>) || {
    appointments: [],
    prescriptions: [],
    lab_tests: [],
    custom: [],
  };

  const medicine_boxes: Record<string, Record<string, unknown>> = {};
  for (let i = 1; i <= 6; i++) {
    const boxId = `B${i}`;
    const m = medicines.find((x) => (x.box_id as string) === boxId);
    if (m && typeof m === 'object') {
      const times = Array.isArray(m.times) ? (m.times as string[]) : [];
      medicine_boxes[boxId] = {
        name: (m.name as string) || 'Medicine',
        quantity: typeof m.quantity === 'number' ? m.quantity : parseInt(String(m.quantity || 0), 10) || 0,
        times,
        dose_per_day: times.length || 1,
        exact_time: times[0] || '08:00',
        instructions: (m.dosage as string) || '',
      };
    }
  }
  const dose_log = dose_logs.map((e) => ({
    timestamp: e.taken_at,
    box: e.box_id || '',
    medicine: '',
    dose_taken: 1,
    remaining: null,
  }));

  return {
    medicine_boxes,
    dose_log,
    alert_settings: (blob.alert_settings as Record<string, unknown>) || {},
    gmail_config: (blob.gmail_config as Record<string, unknown>) || {},
    medical_reminders: {
      appointments: medical_reminders.appointments || [],
      prescriptions: medical_reminders.prescriptions || [],
      lab_tests: medical_reminders.lab_tests || [],
      custom: medical_reminders.custom || [],
    },
  };
}

/** POST full admin state to backend. Backend updates DB and notifies data bus so desktop shows changes. */
export async function postAdminSync(
  accessCode: string,
  payload: {
    medicine_boxes: Record<string, Record<string, unknown>>;
    dose_log: unknown[];
    alert_settings: Record<string, unknown>;
    gmail_config: Record<string, unknown>;
    medical_reminders: Record<string, unknown[]>;
  },
  actAsUserId?: string
): Promise<boolean> {
  const base = getBaseUrl();
  try {
    const body: Record<string, unknown> = { access_code: accessCode, ...payload };
    if (actAsUserId?.trim()) body.act_as_user_id = actAsUserId.trim();
    const res = await fetch(`${base}/admin/sync`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!res.ok) return false;
    return true;
  } catch {
    return false;
  }
}
