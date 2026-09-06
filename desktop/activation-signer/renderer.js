const field = id => document.getElementById(id);
let busy = false;
function clearResult() { field('result').value = ''; field('copy').disabled = true; }
async function action(work) {
  if (busy) return;
  busy = true; field('status').textContent = '';
  field('choose').disabled = field('issue').disabled = field('copy').disabled = true;
  try { await work(); } catch (error) { field('status').textContent = error.message || '操作失败，请重试'; }
  finally {
    busy = false; field('choose').disabled = field('issue').disabled = false;
    field('copy').disabled = !field('result').value;
    field('machine').focus();
  }
}
async function unwrap(promise) { const result = await promise; if (!result.ok) throw new Error(result.message); return result.value; }
field('machine').addEventListener('input', clearResult);
field('choose').onclick = () => action(async () => {
  clearResult(); const result = await unwrap(window.signer.chooseKey());
  if (result) { field('key-path').value = result.keyPath; field('status').textContent = '私钥已选择，可以生成激活码'; }
});
field('issue').onclick = () => action(async () => {
  clearResult();
  const machine = field('machine').value.replace(/[ \t\r\n]/g, '');
  if (!/^LGM1-[0-9A-F]{64}$/.test(machine)) throw new Error('请粘贴完整客户机器码：LGM1- 开头，后接 64 位大写字母或数字');
  field('machine').value = machine;
  const code = await unwrap(window.signer.issue(machine));
  if (field('machine').value !== machine) throw new Error('机器码已改变，请重新生成');
  field('result').value = code; field('status').textContent = '已生成，请复制完整激活码发给客户';
});
field('copy').onclick = () => action(async () => { await unwrap(window.signer.copy()); field('status').textContent = '激活码已复制'; });
action(async () => { const result = await unwrap(window.signer.status()); field('key-path').value = result.keyPath; });
