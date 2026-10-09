import { SETTINGS_SECTIONS as original } from "../../../../upstream/memos/web/src/components/Settings/settingSections";
export * from "../../../../upstream/memos/web/src/components/Settings/settingSections";
export const SETTINGS_SECTIONS = original.filter((section) => ["my-account", "preference", "tags", "memo-export", "spaces"].includes(section.key));
