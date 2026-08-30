import type { FeatureSetting } from "./types";

export const createFeatureSettings = (): FeatureSetting[] => [
  { id: "self-service", name: "自助激活", description: "允许玩家在自助系统激活已发放的 IC 手环。", enabled: true, critical: true },
  { id: "room-alert", name: "房间异常提醒", description: "硬件离线或游戏异常时在管理端显示醒目提醒。", enabled: true },
  { id: "ranking-screen", name: "副屏排行榜", description: "允许电视副屏展示日、月、年度积分榜。", enabled: true },
  { id: "auto-upload", name: "自动数据上传", description: "联网后按所选目标执行数据上传任务。当前仅为 UI 演示。", enabled: false, critical: true },
];
