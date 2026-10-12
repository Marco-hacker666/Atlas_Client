# 線上更新（Online Update）

玩家只要手動裝過一次「有更新功能」的版本，之後每次你發新版，他們的遊戲會自己下載，關遊戲時自動換成新版。

## 運作方式

1. 遊戲啟動時，去 GitHub 最新 Release 讀一個小檔案 `update.properties`（裡面寫著版本號、jar 檔名、檔案指紋和簽章）。
2. 如果比自己新，就在背景下載 jar，然後檢查三件事：
   - 檔案大小對不對
   - 檔案指紋（SHA-256）對不對
   - **簽章**是不是你的私鑰簽的（公鑰內建在 jar 裡）

   任何一項不對就丟掉，不會安裝。
3. 玩家進入世界後，聊天欄會顯示「v2.2.0 已下載並驗證完成，關閉遊戲後會自動更新」。
4. 玩家關閉遊戲後，一個小程式會等遊戲完全關閉（Windows 在遊戲執行中不准換檔），把舊 jar 移到 `.minecraft/myau-update/backup/`，再把新 jar 放進 `mods`。換失敗會自動把舊的放回去。
5. 下次啟動就是新版。

## 第一次設定（只做一次）

**1. 產生金鑰**：在專案資料夾執行

```
java tools/update/GenUpdateKey.java
```

會產生兩個檔案：

- `src/main/resources/myau-update-key.pub` 是**公鑰**（印章的「樣本」），要 commit 上去。
- `update-signing-key.txt` 是**私鑰**（印章本身），**絕對不能 commit**。`.gitignore` 已經擋掉它了。

**2. 把私鑰放進 GitHub**：打開 repo，依序點 Settings → Secrets and variables → Actions → New repository secret。

- Name 填 `UPDATE_SIGNING_KEY`
- Value 貼上 `update-signing-key.txt` 的全部內容

**3. 備份私鑰**：存一份到密碼管理器之類的安全地方，然後刪掉 `update-signing-key.txt`。

> 私鑰弄丟的話，舊版 client 就再也收不到自動更新，玩家只能手動重裝一次。
> 私鑰外流的話，別人就能做出你的 client 會相信的假更新。這時要換一把新金鑰（`--force`），並請玩家手動重裝。

## 以後每次發新版

1. 改 `gradle.properties` 的 `version`（例如 `2.1.2`），commit 並 push。
2. 在 GitHub 建立 Release，tag 填 **`v2.1.2`**（要和 version 一樣，前面加 v），寫好更新說明後按 Publish。
   **不用自己上傳 jar。**
3. 等「Release (online update)」這個 Actions 跑完（大約 5～10 分鐘）。它會自動編譯、簽章，再把 `Myau-Atlas-v2.1.2.jar` 和 `update.properties` 掛到 Release 上。
4. 完成。玩家下次開遊戲就會開始更新。

注意事項：

- tag 和 version 不一致的話，Actions 會直接失敗並告訴你原因，不會發出錯的版本。
- 勾選「Set as a pre-release」的版本**不會**推給玩家，可以拿來給測試者手動下載。
- 如果 Actions 失敗了，修好後到 Actions → Release (online update) → Run workflow，填 tag 重跑就好。

## 玩家可用的指令

| 指令 | 作用 |
|---|---|
| `.update` | 看目前版本、最新版本、狀態 |
| `.update check` | 立刻檢查一次 |
| `.update download` | 「只提醒」模式下手動下載 |
| `.update cancel` | 這次關遊戲時不要安裝 |
| `.update mode auto` | 自動更新（預設） |
| `.update mode notify` | 只提醒，不自動下載 |
| `.update mode off` | 完全關閉 |

設定存在 `config/Myau/updater.properties`。安裝紀錄在 `.minecraft/myau-update/install.log`。

## 程式位置

- `src/main/java/myau/update/`：更新邏輯（`Updater`）、版本比較、簽章驗證、換檔程式（`UpdateInstaller`）、聊天通知
- `src/main/java/myau/command/commands/UpdateCommand.java`：`.update` 指令
- `.github/workflows/release.yml`：發版、簽章
- `tools/update/`：產生金鑰、簽章工具
- `src/test/java/myau/update/UpdaterTest.java`：17 個測試，包含竄改檔案、假簽章、降級、實際換檔
