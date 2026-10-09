import { nativeCall } from "./bridge";
export { stageMemoImport, importStagedMemos, type StagedMemoImport } from "../../../../upstream/memos/web/src/lib/memo-export";
export async function exportMemos(_userName: string, _username: string): Promise<void> {
  const resource = await nativeCall("local.export-archive"); await nativeCall("platform.save", resource);
}
