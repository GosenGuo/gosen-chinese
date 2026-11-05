import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
export function orderClassics(data,mapping){
  const byId=new Map(data.items.map(item=>[item.id,item]));const result=[];let order=0;
  for(const [bookIndex,book] of mapping.books.entries())for(const [lessonIndex,number] of book.ids.entries()){
    const id='classic-'+String(number).padStart(2,'0'),item=byId.get(id);
    assert.ok(item,'Missing classic '+id);assert.ok(!result.some(x=>x.id===id),'Duplicate '+id);
    item.curriculumGroup=item.curriculumGroup||item.book;item.book=book.name;
    item.bookOrder=bookIndex+1;item.lessonOrder=lessonIndex+1;item.order=++order;
    item.orderSource=mapping.sourceUrl;result.push(item);
  }
  assert.equal(result.length,60);assert.equal(byId.size,result.length);
  data.items=result;data.catalog.name='古诗文60篇·教材顺序';
  data.catalog.orderEdition=mapping.edition;data.catalog.orderSource=mapping.sourceUrl;
  data.catalog.note='按人教统编教材的必修上册、必修下册、选择性必修上/中/下册及册内目录排序，其余列为课标补充篇目。保留指定60篇范围，并非对某届海南考试范围的承诺。字形与背诵节选以在用教材为准。';
  return data;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const file=path.join(root,'app/src/main/assets/classics.json'),mapping=JSON.parse(fs.readFileSync(path.join(root,'scripts/sources/textbook-order.json'),'utf8'));
  const data=orderClassics(JSON.parse(fs.readFileSync(file,'utf8')),mapping);
  fs.writeFileSync(file,JSON.stringify(data,null,2)+'\n');
  console.log(JSON.stringify({ordered:data.items.length,books:mapping.books.map(book=>({name:book.name,count:book.ids.length})),first:data.items[0].title}));
}
