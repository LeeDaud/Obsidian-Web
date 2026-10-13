import { Settings, Link } from "lucide-react";
import { Button } from "@/components/ui/button";
import SettingSection from "@/components/Settings/SettingSection";
import { nativeCall } from "./bridge";
import { AppUpdateSection } from "./AppUpdateSection";
import { flushDraftsBeforeUpdate } from "./drafts";
import { useState } from "react";
import toast from "react-hot-toast";
export default function NativeAccountSection() {
  const [pending, setPending] = useState(false);
  const openSettings = async () => {
    if (pending) return;
    setPending(true);
    try { await flushDraftsBeforeUpdate(); await nativeCall("platform.settings"); }
    catch (error) { toast.error(error instanceof Error ? error.message : "设备暂存未完成，请重试"); }
    finally { setPending(false); }
  };
  return <><SettingSection title="账户与投递"><div className="flex flex-col items-start gap-3">
    <Button variant="outline" className="touch-target-coarse" disabled={pending} onClick={() => { void openSettings(); }}><Link className="size-4" />连接原 Memos</Button>
    <Button variant="outline" className="touch-target-coarse" disabled={pending} onClick={() => { void openSettings(); }}><Settings className="size-4" />仓库投递与设备草稿</Button>
  </div></SettingSection><AppUpdateSection /></>;
}
