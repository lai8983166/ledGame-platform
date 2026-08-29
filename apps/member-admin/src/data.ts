import type {
  CardIssueRecord,
  FeatureSetting,
  GameConfig,
  Member,
  PlayRecord,
  Room,
  TransactionRecord,
} from "./types";

const rooms: Room[] = [
  {
    id: "room-01",
    name: "星际穿梭",
    code: "ROOM 01",
    status: "playing",
    gameName: "能量追逐",
    phase: "第 3 / 5 关",
    gameTimeMode: "LIMITED",
    gameTimeRemainingMillis: 754_000,
    gameTimeRunning: false,
    players: [
      { id: "p1", name: "陈小宇", initials: "CY", score: 3280, rank: 1, color: "#5b7cff" },
      { id: "p2", name: "林可欣", initials: "LX", score: 2940, rank: 2, color: "#9b6dff" },
      { id: "p3", name: "周子航", initials: "ZZ", score: 2710, rank: 3, color: "#18b6a4" },
    ],
    hardware: [
      { id: "h1", name: "LED 地砖阵列", location: "主场区", status: "online", detail: "48 / 48 单元在线" },
      { id: "h2", name: "读卡器 A", location: "入口", status: "online", detail: "信号稳定" },
      { id: "h3", name: "音响控制器", location: "设备柜", status: "online", detail: "运行正常" },
    ],
  },
  {
    id: "room-02",
    name: "脉冲竞技场",
    code: "ROOM 02",
    status: "playing",
    gameName: "节奏方阵",
    phase: "第 2 / 4 关",
    gameTimeMode: "LIMITED",
    gameTimeRemainingMillis: 423_000,
    gameTimeRunning: false,
    players: [
      { id: "p4", name: "沈乐宁", initials: "SL", score: 1860, rank: 1, color: "#ff8a65" },
      { id: "p5", name: "王一诺", initials: "WY", score: 1740, rank: 2, color: "#5b7cff" },
    ],
    hardware: [
      { id: "h4", name: "LED 地砖阵列", location: "主场区", status: "warning", detail: "第 17 单元响应延迟" },
      { id: "h5", name: "读卡器 B", location: "入口", status: "online", detail: "信号稳定" },
      { id: "h6", name: "灯光控制器", location: "设备柜", status: "online", detail: "运行正常" },
    ],
  },
  {
    id: "room-03",
    name: "极光之境",
    code: "ROOM 03",
    status: "idle",
    players: [],
    hardware: [
      { id: "h7", name: "LED 地砖阵列", location: "主场区", status: "online", detail: "待机中" },
      { id: "h8", name: "读卡器 C", location: "入口", status: "online", detail: "等待刷卡" },
    ],
  },
  {
    id: "room-04",
    name: "量子迷宫",
    code: "ROOM 04",
    status: "idle",
    players: [],
    hardware: [
      { id: "h9", name: "LED 地砖阵列", location: "主场区", status: "online", detail: "待机中" },
      { id: "h10", name: "读卡器 D", location: "入口", status: "offline", detail: "设备无响应" },
    ],
  },
  {
    id: "room-05",
    name: "光速派对",
    code: "ROOM 05",
    status: "playing",
    gameName: "躲避光束",
    phase: "第 1 / 3 关",
    gameTimeMode: "LIMITED",
    gameTimeRemainingMillis: 1_088_000,
    gameTimeRunning: false,
    players: [
      { id: "p6", name: "许安然", initials: "XA", score: 980, rank: 1, color: "#18b6a4" },
      { id: "p7", name: "顾南星", initials: "GN", score: 820, rank: 2, color: "#9b6dff" },
      { id: "p8", name: "邵雨桐", initials: "SY", score: 760, rank: 3, color: "#ff8a65" },
    ],
    hardware: [
      { id: "h11", name: "LED 地砖阵列", location: "主场区", status: "online", detail: "48 / 48 单元在线" },
      { id: "h12", name: "读卡器 E", location: "入口", status: "online", detail: "信号稳定" },
    ],
  },
  {
    id: "room-06",
    name: "霓虹赛道",
    code: "ROOM 06",
    status: "idle",
    players: [],
    hardware: [
      { id: "h13", name: "LED 地砖阵列", location: "主场区", status: "online", detail: "待机中" },
      { id: "h14", name: "读卡器 F", location: "入口", status: "online", detail: "等待刷卡" },
    ],
  },
];

const members: Member[] = [
  { id: "m1", account: "M20260018", name: "陈小宇", initials: "CY", phone: "13800002716", identityId: "ID-310104-018", status: "active", joinedAt: "2026-08-02", color: "#5b7cff" },
  { id: "m2", account: "M20260017", name: "林可欣", initials: "LX", phone: "18600009032", identityId: "ID-310112-017", status: "active", joinedAt: "2026-08-02", color: "#9b6dff" },
  { id: "m3", account: "M20260016", name: "周子航", initials: "ZZ", phone: "13700006418", identityId: "ID-310115-016", status: "active", joinedAt: "2026-08-01", color: "#18b6a4" },
  { id: "m4", account: "M20260015", name: "沈乐宁", initials: "SL", phone: "15900003027", identityId: "ID-310107-015", status: "active", joinedAt: "2026-07-31", color: "#ff8a65" },
  { id: "m5", account: "M20260014", name: "王一诺", initials: "WY", phone: "13300007290", identityId: "ID-310106-014", status: "inactive", joinedAt: "2026-07-29", color: "#62758a" },
  { id: "m6", account: "M20260013", name: "许安然", initials: "XA", phone: "18800005113", identityId: "ID-310109-013", status: "active", joinedAt: "2026-07-28", color: "#18b6a4" },
  { id: "m7", account: "M20260012", name: "顾南星", initials: "GN", phone: "13600001845", identityId: "ID-310101-012", status: "active", joinedAt: "2026-07-26", color: "#9b6dff" },
  { id: "m8", account: "M20260011", name: "邵雨桐", initials: "SY", phone: "15200004781", identityId: "ID-310118-011", status: "active", joinedAt: "2026-07-24", color: "#ff8a65" },
];

export const createRooms = (): Room[] => structuredClone(rooms);
export const createMembers = (): Member[] => structuredClone(members);

export const cardIssueRecords: CardIssueRecord[] = [
  { id: "CI-260802-041", braceletId: "IC-A8F2-1190", memberName: "陈小宇", memberAccount: "M20260018", issuedAt: "2026-08-02 14:26", duration: 60, status: "activated" },
  { id: "CI-260802-040", braceletId: "IC-B7D1-8824", memberName: "林可欣", memberAccount: "M20260017", issuedAt: "2026-08-02 14:18", duration: 90, status: "activated" },
  { id: "CI-260802-039", braceletId: "IC-C4E8-2206", memberName: "临时玩家", memberAccount: "GUEST-039", issuedAt: "2026-08-02 13:52", duration: 60, status: "unused" },
  { id: "CI-260802-038", braceletId: "IC-D9A3-7415", memberName: "沈乐宁", memberAccount: "M20260015", issuedAt: "2026-08-02 12:35", duration: 45, status: "expired" },
];

export const playRecords: PlayRecord[] = [
  { id: "PL-260802-128", memberName: "陈小宇", braceletId: "IC-A8F2-1190", roomName: "星际穿梭", score: 3280, startedAt: "2026-08-02 14:32", endedAt: "进行中" },
  { id: "PL-260802-127", memberName: "林可欣", braceletId: "IC-B7D1-8824", roomName: "星际穿梭", score: 2940, startedAt: "2026-08-02 14:32", endedAt: "进行中" },
  { id: "PL-260802-126", memberName: "沈乐宁", braceletId: "IC-D9A3-7415", roomName: "脉冲竞技场", score: 4860, startedAt: "2026-08-02 13:20", endedAt: "2026-08-02 13:54" },
  { id: "PL-260802-125", memberName: "许安然", braceletId: "IC-F2C4-3118", roomName: "光速派对", score: 5210, startedAt: "2026-08-02 12:10", endedAt: "2026-08-02 12:46" },
];

export const transactionRecords: TransactionRecord[] = [
  { id: "TX-260802-096", memberName: "陈小宇", memberAccount: "M20260018", amount: 200, tradedAt: "2026-08-02 14:20", status: "success" },
  { id: "TX-260802-095", memberName: "林可欣", memberAccount: "M20260017", amount: 100, tradedAt: "2026-08-02 14:12", status: "success" },
  { id: "TX-260802-094", memberName: "王一诺", memberAccount: "M20260014", amount: 50, tradedAt: "2026-08-02 11:48", status: "refunded" },
  { id: "TX-260802-093", memberName: "顾南星", memberAccount: "M20260012", amount: 150, tradedAt: "2026-08-02 10:34", status: "success" },
];

export const gameConfigs: GameConfig[] = [
  { id: "game-energy", name: "能量追逐", category: "竞速协作", levels: ["能量启动", "光带穿梭", "核心争夺", "终极冲刺", "能量汇聚"], lives: 3, scoringRule: "踩中蓝色能量格 +20，连续命中每次额外 +5；误踩红色格扣除 1 点生命值。", assetPath: "/assets/games/energy-chase/v3/production/led-floor/", components: ["FloorGrid", "EnergyOrb", "ComboMeter", "RoundTimer"], status: "enabled" },
  { id: "game-rhythm", name: "节奏方阵", category: "节奏反应", levels: ["节拍热身", "双色连击", "四向节奏", "极速返场"], lives: 5, scoringRule: "完美踩点 +100，普通踩点 +60，未命中扣除 1 点生命值；20 连击获得 1.5 倍积分。", assetPath: "/assets/games/rhythm-matrix/v2/live/audio-and-panels/", components: ["BeatMap", "StepPanel", "AccuracyRing", "MusicController"], status: "enabled" },
  { id: "game-laser", name: "躲避光束", category: "敏捷闯关", levels: ["低速扫描", "交叉光束", "脉冲风暴"], lives: 3, scoringRule: "每存活 1 秒 +10，通过安全区额外 +200；触碰危险格扣除 1 点生命值。", assetPath: "/assets/games/laser-dodge/v1/runtime/scene-components/", components: ["LaserField", "SafeZone", "LifeBar", "SurvivalClock"], status: "enabled" },
  { id: "game-maze", name: "量子迷宫", category: "解谜协作", levels: ["入口协议", "镜像通道", "量子核心"], lives: 4, scoringRule: "到达节点 +80，团队同步触发 +300；超时每 10 秒扣 50 分。", assetPath: "/assets/games/quantum-maze/v1/staging/interactive-nodes/", components: ["MazeNode", "TeamSync", "PathGuide", "HintPulse"], status: "disabled" },
];

export const createFeatureSettings = (): FeatureSetting[] => [
  { id: "self-service", name: "自助激活", description: "允许玩家在自助系统激活已发放的 IC 手环。", enabled: true, critical: true },
  { id: "room-alert", name: "房间异常提醒", description: "硬件离线或游戏异常时在管理端显示醒目提醒。", enabled: true },
  { id: "ranking-screen", name: "副屏排行榜", description: "允许电视副屏展示日、月、年度积分榜。", enabled: true },
  { id: "auto-upload", name: "自动数据上传", description: "联网后按所选目标执行数据上传任务。当前仅为 UI 演示。", enabled: false, critical: true },
];
