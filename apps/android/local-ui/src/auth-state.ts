// A presentation identity, never a Memos/GitHub credential or authentication grant.
export const getAccessToken = () => "local-device-presentation";
export const hasStoredToken = () => true;
export const isTokenExpired = (_buffer = 0) => false;
export const setAccessToken = (_token: string | null, _expiresAt?: Date) => {};
export const clearAccessToken = () => {};
export const REQUEST_TOKEN_EXPIRY_BUFFER_MS = 30000;
export const FOCUS_TOKEN_EXPIRY_BUFFER_MS = 120000;
