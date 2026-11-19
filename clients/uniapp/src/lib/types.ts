export type PlatformCode =
  | "ONELAP"
  | "XINGZHE"
  | "GARMIN"
  | "COROS"
  | "IGPSPORT"
  | "STRAVA"
  | "LOCAL";
export type SportCategory = "CYCLING" | "RUNNING" | "OTHER";
export interface PlatformInfo {
  code: PlatformCode;
  name: string;
  authMode: string;
  experimental?: boolean;
}
export interface WebSessionFields {
  cookie?: string;
  token?: string;
  refreshToken?: string;
  csrf?: string;
  ssoCookie?: string;
  externalUserId?: string;
  userAgent?: string;
}
export interface Config {
  wechatLoginEnabled: boolean;
  webLoginEnabled?: boolean;
  passwordLoginEnabled?: boolean;
  devLoginEnabled: boolean;
  stravaOAuthConfigured?: boolean;
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
export interface ActivityVariant {
  id: string;
  sourcePlatform: PlatformCode;
  remoteActivityId?: string;
  fileSize?: number;
  qualityScore?: number;
  sha256?: string;
  isPrimary?: boolean;
  createdAtEpochMs?: number;
}
export interface ActivityCopy {
  remoteActivityId: string;
  platform: PlatformCode;
  accountId?: string;
}
export interface ProfilePoint {
  seconds: number;
  speedMps?: number;
  altitudeMeters?: number;
}
export interface Activity {
  id: string;
  title: string;
  startTimeEpochMs: number;
  distanceMeters: number;
  elapsedSeconds: number;
  movingSeconds?: number;
  sportType?: string;
  sourcePlatform?: PlatformCode;
  platforms?: PlatformCode[];
  variantCount: number;
  needsReview: boolean;
  reviewCandidateId?: string;
  reviewCandidate?: Activity;
  avgHeartRate?: number;
  avgPower?: number;
  elevationGain?: number;
  warnings?: string[];
  track?: { lat: number; lon: number }[];
  profile?: ProfilePoint[];
  copies?: ActivityCopy[];
  variants?: ActivityVariant[];
}
export interface Account {
  id: string;
  platform: PlatformCode;
  displayName: string;
  status: string;
  lastSyncAt?: number;
  lastError?: string;
  pullSupported?: boolean;
}
export interface Rule {
  id?: string;
  sourcePlatform: PlatformCode;
  destinationAccountId: string;
  enabled: boolean;
  includeHistory: boolean;
  sportTypes?: string[];
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
export interface PullProgress {
  fetched: number;
  existingSkipped: number;
  imported: number;
  merged: number;
  needsReview: number;
  skippedNoFit: number;
  pages: number;
}
export interface PullJobStatus {
  id: string;
  accountId: string;
  platform: PlatformCode;
  displayName?: string;
  accountStatus?: string;
  status: string;
  message?: string;
  progress: PullProgress;
  createdAtEpochMs: number;
  updatedAtEpochMs: number;
}
export interface PullStartResponse {
  jobIds: string[];
  accounts: {
    accountId: string;
    platform: PlatformCode;
    displayName?: string;
    jobId: string;
    status: string;
  }[];
  skippedPlatforms: {
    accountId: string;
    platform: PlatformCode;
    displayName?: string;
    reason: string;
  }[];
}
export interface PullStatusResponse {
  jobs: PullJobStatus[];
  activeCount: number;
  totals: PullProgress;
}
export interface Page<T> {
  items: T[];
  total: number;
}
