import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const rawPath = path.resolve(project, '../../work/gsw72.html');
const html = fs.readFileSync(rawPath, 'utf8').replace(/<!--[\s\S]*?-->/g, '');
const anchors = [...html.matchAll(/<a name="(wb\d+|wxb\d+|wx\d+|q\d+)" id="\1"><\/a>/g)];
const decode = value => value.replace(/&nbsp;|&#160;/g, ' ').replace(/&ldquo;/g, '“').replace(/&rdquo;/g, '”').replace(/&lsquo;/g, '‘').replace(/&rsquo;/g, '’').replace(/&mdash;/g, '—').replace(/&middot;/g, '·').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
const plain = value => decode(value.replace(/<[^>]+>/g, '')).trim();
const items = [];
for (let i=0;i<anchors.length;i++) {
  const anchor = anchors[i];
  const number = Number(anchor[1].replace(/\D/g, ''));
  if (anchor[1].startsWith('wx') && !anchor[1].startsWith('wxb')) continue;
  const block = html.slice(anchor.index, anchors[i+1]?.index ?? html.indexOf('<script',anchor.index));
  const kind = anchor[1].startsWith('w') ? 'prose' : 'poem';
  const titles = [...block.matchAll(/<div class="sc-mz">([\s\S]*?)<\/div>/g)].map(x=>plain(x[1]));
  const author = plain(block.match(/<div class="sc-author">([\s\S]*?)<\/div>/)?.[1] ?? '佚名').replace(/\[[^\]]+\]\s*/g,'');
  const bodyFragment = block.slice(block.indexOf('<div class="sc-wz">')+19).split('<div class="sc-li">')[0];
  let body = decode(bodyFragment.replace(/<br\s*\/?\s*>/gi,'\n').replace(/<\/(?:div|p)>/gi,'\n').replace(/<[^>]+>/g,''))
    .split(/\r?\n/).map(x=>x.replace(/[\t \u3000]/g,'').trim()).filter(Boolean).join('\n');
  if (anchor[1] === 'wb2') body = body.split('\n').filter(x=>!x.startsWith('故不登高山')&&!x.startsWith('南方有鸟')&&!x.startsWith('物类之起')).join('\n').replace('君子性非异也','君子生非异也');
  const order = kind==='prose' ? number+(anchor[1].startsWith('wxb')?10:0) : number+20;
  let title = titles.join('·');
  if(anchor[1]==='wb1') title='《论语》十二章';
  if(anchor[1]==='wb3') title='屈原列传（节选）';
  if(anchor[1]==='wxb1') title='子路、曾皙、冉有、公西华侍坐';
  if(anchor[1]==='wxb2') title='报任安书（节选）';
  if(anchor[1]==='wxb4') title='礼运·大道之行也';
  if(anchor[1]==='q3') title='离骚（节选）';
  if(anchor[1]==='q39') title='长亭送别·端正好（节选）';
  body = body.replace(/\(此段教材无\)/g,'').replace(/!+/g,'！').replace(/\?/g,'？').replace(/;/g,'；');
  items.push({ id:`classic-${String(order).padStart(2,'0')}`, module:'classics',title, author:anchor[1]==='wb1'?'孔子及其弟子':author,kind,book:kind==='poem'?'诗词曲40首':anchor[1].startsWith('wxb')?'文言文·选择性必修10篇':'文言文·必修10篇',order,body,sourceLabel:'古典原文：中国教育在线公开汇编；注释、练习为本软件参考整理',sourceUrl:`https://www.eol.cn/e_html/gsw72/index.html#${anchor[1]}`,verification:'通行原文已收录；教材字形、标点及指定背诵范围待逐字核对。译文为重点句参考译文，尚非全文逐句译文。',notes:[],translations:[],questions:[]});
}
if(items.length!==60) throw Error(`Expected 60 entries; got ${items.length}`);
fs.writeFileSync(path.join(project,'app/src/main/assets/classics.json'), JSON.stringify({schemaVersion:1,catalog:{name:'古诗文60篇·学习目录',note:'按课标必修文言文10篇、选择性必修文言文10篇、诗词曲40首组织，目录并非对当届海南考试范围的承诺。原文通行版本尚待按用户在用教材逐字核验；当前先提供重点词句注释和参考译文。',sourceUrl:'https://zh.wikisource.org/zh-hans/新高考过渡时期语文学科背诵篇目说明'},items},null,2)+'\n');
console.log(items.map(x=>`${x.id}\t${x.title}\t${x.body.length}`).join('\n'));
