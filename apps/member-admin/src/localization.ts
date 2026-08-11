import {
  type AuthoredCatalogs,
  applyQueuedTranslations,
  createCompleteCatalogs,
  type PlatformLocale,
} from "@ledgame/platform-shared-ui";
import persistedTranslations from "../../../i18n/translations.json";

export const MEMBER_ADMIN_LOCALE_STORAGE_KEY = "ledgame.member-admin.locale";

export const memberAdminBaseCatalog = {
  language: "Language",
  chooseLanguage: "Choose language",
  close: "Close",
  openNavigation: "Open navigation",
  closeNavigation: "Close navigation",
  mainFunctions: "Main functions",
  systemHealthy: "System healthy",
  roomsConnected: "6 rooms connected",
  storeManager: "Store manager",
  demoData: "Demo data",
  notifications: "View notifications",
  navWristbands: "Wristbands",
  navOverview: "Overview",
  navRooms: "Rooms",
  navMembers: "Members",
  navRecords: "Records & data",
  navRanking: "Leaderboard",
  navSettings: "Settings",
  descWristbands: "Charge, activate, inspect and recycle wristbands",
  descOverview: "Today's key store metrics and live status",
  descRooms: "Monitor room progress, scores and device status",
  descMembers: "Search and maintain member profiles and balances",
  descRecords: "Review wristband, transaction and game records",
  descRanking: "Preview daily, monthly and yearly rankings",
  descSettings: "Configure wristband rules and system options",
};

export type MemberAdminMessageKey = keyof typeof memberAdminBaseCatalog;

type Copy = Partial<Record<MemberAdminMessageKey, string>>;

const navigation = (
  language: string,
  chooseLanguage: string,
  values: [string, string, string, string, string, string, string],
): Copy => ({
  language,
  chooseLanguage,
  navWristbands: values[0],
  navOverview: values[1],
  navRooms: values[2],
  navMembers: values[3],
  navRecords: values[4],
  navRanking: values[5],
  navSettings: values[6],
});

export const memberAdminAuthoredCatalogs = {
  "zh-CN": {
    language: "语言",
    chooseLanguage: "选择语言",
    close: "关闭",
    openNavigation: "打开导航",
    closeNavigation: "关闭导航",
    mainFunctions: "管理中心",
    systemHealthy: "系统运行正常",
    roomsConnected: "6 个房间已连接",
    storeManager: "门店管理员",
    demoData: "演示数据",
    notifications: "查看通知",
    navWristbands: "手环管理",
    navOverview: "运营总览",
    navRooms: "房间管理",
    navMembers: "会员管理",
    navRecords: "记录与数据",
    navRanking: "积分排行",
    navSettings: "系统设置",
    descWristbands: "完成手环充时、激活、查询与回收",
    descOverview: "门店今天的关键数据与实时状态",
    descRooms: "掌握房间进度、积分与设备状态",
    descMembers: "查询和维护会员资料与余额",
    descRecords: "集中查看手环、交易与游戏记录",
    descRanking: "查看日、月、年度积分排行榜",
    descSettings: "配置手环规则与系统选项",
  },
  "es-ES": navigation("Idioma", "Elegir idioma", ["Pulseras", "Resumen", "Salas", "Miembros", "Registros y datos", "Clasificación", "Ajustes"]),
  "pt-PT": navigation("Idioma", "Escolher idioma", ["Pulseiras", "Visão geral", "Salas", "Membros", "Registos e dados", "Classificação", "Definições"]),
  "fr-FR": navigation("Langue", "Choisir la langue", ["Bracelets", "Vue d’ensemble", "Salles", "Membres", "Données", "Classement", "Paramètres"]),
  "de-DE": navigation("Sprache", "Sprache wählen", ["Armbänder", "Übersicht", "Räume", "Mitglieder", "Daten", "Rangliste", "Einstellungen"]),
  "pl-PL": navigation("Język", "Wybierz język", ["Opaski", "Przegląd", "Pokoje", "Członkowie", "Dane", "Ranking", "Ustawienia"]),
  "ru-RU": navigation("Язык", "Выберите язык", ["Браслеты", "Обзор", "Комнаты", "Участники", "Данные", "Рейтинг", "Настройки"]),
  "vi-VN": navigation("Ngôn ngữ", "Chọn ngôn ngữ", ["Vòng tay", "Tổng quan", "Phòng", "Thành viên", "Dữ liệu", "Xếp hạng", "Cài đặt"]),
  "it-IT": navigation("Lingua", "Scegli lingua", ["Braccialetti", "Panoramica", "Stanze", "Membri", "Dati", "Classifica", "Impostazioni"]),
  "cs-CZ": navigation("Jazyk", "Zvolit jazyk", ["Náramky", "Přehled", "Místnosti", "Členové", "Data", "Žebříček", "Nastavení"]),
  "ko-KR": navigation("언어", "언어 선택", ["손목밴드", "개요", "게임실", "회원", "기록 및 데이터", "순위", "설정"]),
  "ro-RO": navigation("Limbă", "Alege limba", ["Brățări", "Prezentare", "Camere", "Membri", "Date", "Clasament", "Setări"]),
  "ar-SA": navigation("اللغة", "اختر اللغة", ["الأساور", "نظرة عامة", "الغرف", "الأعضاء", "السجلات والبيانات", "الترتيب", "الإعدادات"]),
} satisfies AuthoredCatalogs<typeof memberAdminBaseCatalog>;

export const memberAdminCatalogs = createCompleteCatalogs(
  memberAdminBaseCatalog,
  applyQueuedTranslations(
    memberAdminBaseCatalog,
    memberAdminAuthoredCatalogs,
    persistedTranslations.applications.memberAdmin.targets,
  ),
);

export function memberAdminMessage(locale: PlatformLocale, key: MemberAdminMessageKey): string {
  return memberAdminCatalogs[locale][key];
}
