// Mechanical transformation of a private, locally supplied APK derivative.
// Does not remove login, signature checks, certificate pinning or app licensing.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const root=path.dirname(fileURLToPath(import.meta.url));
const host=path.join(root,'build/host-104');
const source=process.argv[2];
if(!source) throw new Error('Usage: node android-addon/package.mjs /path/to/RayNeo_AI_1.0.4.apk');
const dex=path.join(root,'build/dex/classes.dex');
if(!fs.existsSync(dex)) throw new Error('Build the original addon first: bash android-addon/build.sh');
const sha=crypto.createHash('sha256').update(fs.readFileSync(source)).digest('hex');
if(sha!=='ef2e7dd346ca478e13d0f3f1bf31fa61412e44fb9b4ca9cf0d3e864ac608584b') throw new Error('Unsupported APK; refusing to guess offsets/classes');

const APKTOOL = process.env.TURBOIO_APKTOOL || 'apktool';
// Windows Defender real-time scanning races with apktool's multi-threaded smali
// writes and intermittently returns "Access is denied" -> apktool skips the class.
// Serialize the decode (-j 1) so only one file is open at a time. Override with
// TURBOIO_JOBS if you run on a host without AV interference and want speed.
const JOBS = process.env.TURBOIO_JOBS || '1';
const apktoolArgs = APKTOOL.endsWith('.jar')
  ? ['-jar', APKTOOL, 'd', '-j', JOBS, '--no-res', '--output', host, source]
  : ['d', '-j', JOBS, '--no-res', '--output', host, source];
const apktoolCmd = APKTOOL.endsWith('.jar') && process.env.TURBOIO_JAVA
  ? process.env.TURBOIO_JAVA : APKTOOL;
if(!fs.existsSync(host)) execFileSync(apktoolCmd,apktoolArgs,{stdio:'inherit'});

function wrap(file,name,signature,args,returnMode) {
  let text=fs.readFileSync(file,'utf8');
  const original='turboioOriginal_'+name;
  const header='.method public final '+name+signature;
  // Replace our generated wrapper on repeated builds, retain the original body.
  if(text.includes(original+'(')) {
    const start=text.indexOf(header+'\n');
    if(start<0)throw new Error('Generated wrapper missing');
    const end=text.indexOf('.end method',start);
    text=text.slice(0,start)+text.slice(end+'.end method'.length);
  } else {
  if(text.split(header).length!==2) throw new Error('Method signature mismatch: '+name);
  text=text.replace(header,'.method public final '+original+signature);
  }
  const className=text.match(/^\.class[^\n]* (L[^;]+;)$/m)?.[1];
  if(!className) throw new Error('Class header missing');
  const invoke='invoke-virtual/range {'+args+'}, '+className+'->'+original+signature;
  const map={onAsrResult:'dispatchAsr(Ljava/lang/Object;Ljava/lang/String;ZLjava/lang/String;)V',
    onNlpResult:'dispatchNlp(Ljava/lang/Object;Ljava/lang/Object;)V',onResponseComplete:'dispatchComplete(Ljava/lang/Object;)V',
    onPostResume:'install(Landroid/app/Activity;)V',
    onActivityResult:'onActivityResult(Landroid/app/Activity;IILandroid/content/Intent;)V'};
  const bridge='invoke-static/range {'+args+'}, Lcom/turboio/addon/AyaSuperAddon;->'+map[name];
  // 「先原后新」：官方逻辑必须最先跑完（onPostResume 要恢复官方生命周期，
  // onActivityResult 要把结果转发给 Flutter 引擎），扩展只做旁观与附加分发。
  const officialFirst = name==='onPostResume' || name==='onActivityResult';
  const body=!officialFirst
    ? `    :turboio_try\n    ${bridge}\n    :turboio_try_end\n    return-void\n    :turboio_error\n    move-exception v0\n    ${invoke}\n    return-void`
    : `    ${invoke}\n    :turboio_try\n    ${bridge}\n    :turboio_try_end\n    return-void\n    :turboio_error\n    move-exception v0\n    return-void`;
  text+='\n'+header+'\n    .locals 1\n'+body+'\n    .catch Ljava/lang/Throwable; {:turboio_try .. :turboio_try_end} :turboio_error\n.end method\n';
  fs.writeFileSync(file,text);
}
const listener=path.join(host,'smali_classes2/H7/c$c.smali');
wrap(listener,'onAsrResult','(Ljava/lang/String;ZLjava/lang/String;)V','p0 .. p3','after');
wrap(listener,'onNlpResult','(Lcom/rayneo/airuntime/controller/NlpResult;)V','p0 .. p1','guard');
wrap(listener,'onResponseComplete','()V','p0 .. p0','guard');
wrap(path.join(host,'smali_classes2/com/rayneo/venus/MainActivity.smali'),'onPostResume','()V','p0 .. p0','after');
/**
 * 电子书 / 提词器用系统文件选择器选 .txt，需要拿到 onActivityResult 的结果。
 *
 * ★ 绝不能给 MainActivity 新增 onActivityResult 覆写！★
 *   MainActivity 的父类 Ls9/d 把 onActivityResult 声明为 `public final`，
 *   子类覆盖 final 方法会被 ART verifier 判为非法，抛 VerifyError。
 *   MainActivity 是启动入口，类加载失败 = 点开 App 立刻「已停止运行」，
 *   而 VerifyError 发生在验证阶段，Java 侧任何 catch(Throwable) 都拦不住。
 *   所以正确做法是 wrap 父类 Ls9/d 自己的方法体（改名 + 新增转发），
 *   既不违反 final 约束，也不改变对官方子类的行为。
 *
 * ★ Windows 大小写陷阱 ★
 *   Ls9/d（小写）与 LS9/D（大写枚举）在大小写不敏感的文件系统上是同一个路径。
 *   apktool 会把冲突的类挪到 `s9.1/` 目录，因此这里必须实际探测文件位置。
 */
function hookActivityResult() {
  const wanted = /^\.class[^\n]* Ls9\/d;$/m;
  const roots = ['smali', 'smali_classes2', 'smali_classes3', 'smali_classes4']
    .map(d => path.join(host, d)).filter(d => fs.existsSync(d));
  const found = [];
  // 只扫 s9* 开头的目录（apktool 会把冲突类放进 s9.1/ 这类兄弟目录），
  // 30k 个 smali 全量读太慢，这里按目录名收窄。
  const walk = dir => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      if (!/^s9(\.\d+)?$/.test(entry.name)) continue;
      const sub = path.join(dir, entry.name);
      for (const file of fs.readdirSync(sub)) {
        if (!file.endsWith('.smali')) continue;
        const full = path.join(sub, file);
        if (wanted.test(fs.readFileSync(full, 'utf8'))) found.push(full);
      }
    }
  };
  for (const r of roots) walk(r);
  if (found.length !== 1) throw new Error('Ls9/d (Activity base) not found uniquely: ' + found.join(', '));
  wrap(found[0], 'onActivityResult', '(IILandroid/content/Intent;)V', 'p0 .. p3', 'after');
  return found[0];
}
hookActivityResult();
// Preserve all official event delivery, observe only business-19 metadata afterward.
const eventFile=path.join(host,'smali_classes2/com/rayneo/rayneo_venus_sdk_plugin/j.smali');
let eventText=fs.readFileSync(eventFile,'utf8');
const eventHeader='.method public final z(Ljava/lang/String;Ljava/util/Map;)V';
if(eventText.includes('turboioOriginal_z(')) {
  const start=eventText.indexOf(eventHeader+'\n');if(start<0)throw new Error('Missing event wrapper');
  const end=eventText.indexOf('.end method',start);eventText=eventText.slice(0,start)+eventText.slice(end+11);
} else {
  if(eventText.split(eventHeader).length!==2)throw new Error('Event signature mismatch');
  eventText=eventText.replace(eventHeader,'.method public final turboioOriginal_z(Ljava/lang/String;Ljava/util/Map;)V');
}
eventText+='\n'+eventHeader+`\n    .locals 1
    invoke-virtual {p0, p1, p2}, Lcom/rayneo/rayneo_venus_sdk_plugin/j;->turboioOriginal_z(Ljava/lang/String;Ljava/util/Map;)V
    :turboio_nav_try
    invoke-static {p1, p2}, Lcom/turboio/addon/NavGlasses;->event(Ljava/lang/String;Ljava/util/Map;)V
    :turboio_nav_end
    return-void
    :turboio_nav_error
    move-exception v0
    return-void
    .catch Ljava/lang/Throwable; {:turboio_nav_try .. :turboio_nav_end} :turboio_nav_error
.end method\n`;
fs.writeFileSync(eventFile,eventText);
// Local adaptation: stage the addon dex INSIDE the unpacked tree before
// repacking, so apktool itself computes every ZIP offset. Appending the entry
// afterwards corrupted offsets whenever the archive carried a signing block,
// because the existing local-header offsets no longer matched the file start.
// ---------------------------------------------------------------------------
// INlpInterceptor 子类：必须以 smali 形式交给 apktool 编译。
//
// 原因：INlpInterceptor 是宿主里的 public abstract class，而 addon 的
// javac classpath 只有 android.jar（没有宿主类），所以无法用 Java 编译；
// 运行时动态代理又不支持 abstract class。smali-baksmali jar 只含反汇编器
// 不含汇编器，所以最后的办法是：把 smali 源文件摆进 apktool 的 smali_classes2，
// 让它跟着其余两万多个类一起编译 —— 这样新类与官方的 INlpInterceptor /
// D7/V0 落在同一个 dex，链接天然正确，也不需要额外工具链。
//
// 必须放在 smali_classes2：它要引用的 INlpInterceptor 与 D7/V0 都在这个 dex。
const interceptorSource=path.join(root,'smali/com/turboio/addon/TurboNlpInterceptor.smali');
const interceptorTarget=path.join(host,'smali_classes2/com/turboio/addon/TurboNlpInterceptor.smali');
if(!fs.existsSync(interceptorSource)) throw new Error('Missing smali source: '+interceptorSource);
fs.mkdirSync(path.dirname(interceptorTarget),{recursive:true});
fs.copyFileSync(interceptorSource,interceptorTarget);

const staged=path.join(root,'build/classes4.dex');
fs.copyFileSync(dex,staged);
// Rebuilt on every run: the unpacked tree is reused across builds, so drop any
// dex staged by a previous run before writing the fresh one.
//
// ★ 不要用 fs.rmSync 先删 ★
//   在带"安全删除保护"的宿主里（CodeBuddy/WorkBuddy 的 safe-delete shim），
//   自动化批量删除会在阈值后直接抛错：
//       [SAFE_DELETE_BULK_CONFIRM_REQUIRED] count=106 threshold=50
//   构建因此中断在最后一步。而 fs.copyFileSync 本身就是**截断覆盖**，
//   根本不需要先删 —— 去掉这一步既等价又不触发保护。
fs.copyFileSync(dex,path.join(host,'classes4.dex'));
const output=path.join(root,'build/TurboIO-RayNeo-1.0.4-unsigned.apk');
const buildArgs = APKTOOL.endsWith('.jar')
  ? ['-jar', APKTOOL, 'b', host, '-o', output]
  : ['b', host, '-o', output];
execFileSync(apktoolCmd, buildArgs, {stdio:'inherit'});
const report={sourceSha256:sha,sourceVersion:'1.0.4 (195)',originalSignaturePreserved:false,
  changes:['ASR observer','NLP/complete guards','onPostResume native entry','business19 display events',
    'classes4.dex addon','INlpInterceptor subclass (smali, intent router takeover)'],
  credentialsBundled:false,outputSha256:crypto.createHash('sha256').update(fs.readFileSync(output)).digest('hex'),
  installed:false,nonRootValidated:false};
fs.writeFileSync(path.join(root,'build/package-report.json'),JSON.stringify(report,null,2)+'\n');
console.log('Unsigned private derivative prepared; signing and non-root acceptance still required.');
