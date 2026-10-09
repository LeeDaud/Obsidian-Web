import { Settings, Link } from "lucide-react";
import { Button } from "@/components/ui/button";
import SettingSection from "@/components/Settings/SettingSection";
import { nativeCall } from "./bridge";
export default function NativeAccountSection() {
  return <SettingSection title="账户与投递"><div className="flex flex-col items-start gap-3">
    <Button variant="outline" onClick={() => { void nativeCall("platform.settings"); }}><Link className="size-4" />连接原 Memos</Button>
    <Button variant="outline" onClick={() => { void nativeCall("platform.settings"); }}><Settings className="size-4" />仓库投递与设备草稿</Button>
  </div></SettingSection>;
}
