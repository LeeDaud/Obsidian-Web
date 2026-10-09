import { fromJson } from "../../../../upstream/memos/web/node_modules/@bufbuild/protobuf/dist/esm/index.js";
import { AttachmentSchema } from "@/types/proto/api/v1/attachment_service_pb";
import type { LocalFile } from "@/components/MemoEditor/types/attachment";
import { uploadLocalFile } from "./drafts";
export const uploadService = {
  async uploadFile(file: LocalFile) { return fromJson(AttachmentSchema, await uploadLocalFile(file)); },
  async uploadFiles(files: LocalFile[]) {
    const result: Awaited<ReturnType<typeof this.uploadFile>>[] = []; let next = 0;
    await Promise.all(Array.from({ length: Math.min(4, files.length) }, async () => { for (let index = next++; index < files.length; index = next++) result[index] = await this.uploadFile(files[index]); }));
    return result;
  },
};
