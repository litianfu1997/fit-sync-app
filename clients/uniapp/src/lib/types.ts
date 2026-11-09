export type PlatformCode =
  | "ONELAP"
  | "XINGZHE"
  | "GARMIN"
  | "COROS"
  | "IGPSPORT"
  | "STRAVA"
  | "LOCAL";
export interface PlatformInfo {
  code: PlatformCode;
  name: string;
  authMode: string;
  experimental?: boolean;
}
export interface Config {
  wechatLoginEnabled: boolean;
  webLoginEnabled?: boolean;
  passwordLoginEnabled?: boolean;
  devLoginEnabled: boolean;
  ads: {
    enabled: boolean;
    nativeAdUnitId?: string;
    rewardedAdUnitId?: string;
    rewardVerificationEnabled?: boolean;
  };
  platforms: PlatformInfo[];
}
export interface User {
  id: string;
  displayName: string;
  username?: string;
  hasPassword?: boolean;
  hasWechat?: boolean;
}
export interface LoginResult {
  token: string;
  user: User;
}
export interface Dashboard {
  activityCount: number;
  totalDistanceMeters: number;
  pendingJobs: number;
  connectedAccounts: number;
  lastSyncAt?: number;
}
export interface Activity {
  id: string;
  title: string;
  startTimeEpochMs: number;
  distanceMeters: number;
  elapsedSeconds: number;
  sportType?: string;
  sourcePlatform?: PlatformCode;
  variantCount: number;
  needsReview: boolean;
  reviewCandidateId?: string;
  avgHeartRate?: number;
  avgPower?: number;
  elevationGain?: number;
  warnings?: string[];
  track?: { lat: number; lon: number }[];
  variants?: { id: string; sourcePlatform: string; fileName: string }[];
}
export interface Account {
  id: string;
  platform: PlatformCode;
  displayName: string;
  status: string;
  lastSyncAt?: number;
  lastError?: string;
}
export interface Rule {
  id?: string;
  sourcePlatform: PlatformCode;
  destinationAccountId: string;
  enabled: boolean;
  includeHistory: boolean;
}
export interface Job {
  id: string;
  kind: string;
  status: string;
  platform?: string;
  activityTitle?: string;
  message?: string;
  createdAtEpochMs: number;
  updatedAtEpochMs: number;
}
export interface Page<T> {
  items: T[];
  total: number;
}
