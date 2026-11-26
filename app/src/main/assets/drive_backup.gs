// たぺいノート：Google ドライブに控えを保存する受け口
//
// 使い方（最初に1回だけ）
//  1. https://script.google.com で「新しいプロジェクト」を作り、中身を全部消してこれを貼り付けて保存
//  2. 「デプロイ」→「新しいデプロイ」→ 種類「ウェブアプリ」
//     次のユーザーとして実行：自分　／　アクセスできるユーザー：全員　→「デプロイ」→ アクセスを承認
//  3. 出てきた「ウェブアプリの URL」をアプリの設定に貼り、「つながるか試す」
//
// ドライブの「たっぴぃず録音」フォルダの中に、記録ごとのフォルダを作って保存します。
// 合言葉（TOKEN）が合わない送信は受け付けません。

const TOKEN = '@@TOKEN@@';
const ROOT_NAME = 'たっぴぃず録音';

function doPost(e) {
  try {
    const req = JSON.parse(e.postData.contents);
    if (req.token !== TOKEN) return json_({ ok: false, error: '合言葉が違います' });
    if (req.action === 'ping') return json_({ ok: true, folder: ROOT_NAME });

    const lock = LockService.getScriptLock();
    lock.waitLock(30000);
    let dir;
    try {
      const root = folder_(DriveApp.getRootFolder(), ROOT_NAME);
      dir = folder_(root, req.folder);
    } finally {
      lock.releaseLock();
    }
    const blob = Utilities.newBlob(Utilities.base64Decode(req.data), req.mime, req.name);
    // 同じ名前の古いものはゴミ箱へ（上書き）
    const old = dir.getFilesByName(req.name);
    while (old.hasNext()) old.next().setTrashed(true);
    const f = dir.createFile(blob);
    return json_({ ok: true, id: f.getId() });
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

function folder_(parent, name) {
  const it = parent.getFoldersByName(name);
  return it.hasNext() ? it.next() : parent.createFolder(name);
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
