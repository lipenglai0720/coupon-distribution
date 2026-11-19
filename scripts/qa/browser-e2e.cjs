
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || "playwright");

const origin = process.env.COUPON_URL || "http://localhost:8080";
const accountFile = process.env.COUPON_BROWSER_ACCOUNTS || ".local/accounts.json";
const merchantName = process.env.COUPON_BROWSER_MERCHANT || "merchant-lab";
const customerName = process.env.COUPON_BROWSER_CUSTOMER || "customer-lab";

async function signIn(page, account) {
  await page.goto(origin);
  await page.locator("#login [name=username]").fill(account.username);
  await page.locator("#login [name=password]").fill(account.password);
  await page.locator("#login button").click();
  await page.locator("#workspace").waitFor({state: "visible"});
  assert.equal(await page.title(), "优惠券分发与核销系统");
  await page.locator("#templateList .coupon, #templateList .empty").first().waitFor();
}

async function merchantConfirm(page, action, expected) {
  if (action !== "query") page.once("dialog", dialog => dialog.accept());
  await page.locator('#redemptionForm [value="' + action + '"]').click();
  await page.waitForFunction(status => document.querySelector("#orderResult").textContent.includes(status), expected);
}

async function main() {
  const accounts = JSON.parse(fs.readFileSync(accountFile, "utf8"));
  const merchant = accounts.find(account => account.username === merchantName);
  const customer = accounts.find(account => account.username === customerName);
  assert(merchant && customer, "Configure merchant and customer browser accounts");
  const launchOptions = {headless: true};
  if (process.env.CHROME_PATH) launchOptions.executablePath = process.env.CHROME_PATH;
  const browser = await chromium.launch(launchOptions);
  const errors = [];
  try {
    const m = await browser.newPage({viewport: {width: 1280, height: 960}});
    const c = await browser.newPage({viewport: {width: 390, height: 844}});
    for (const page of [m, c]) page.on("pageerror", error => errors.push(error.message));
    await signIn(m, merchant);

    const cardName = "周末活动 · 满100减20 · " + Date.now();
    const created = m.waitForResponse(response =>
      response.url().endsWith("/api/templates") && response.request().method() === "POST");
    await m.locator("#createForm [name=name]").fill(cardName);
    await m.locator("#createForm button").click();
    const createResponse = await created;
    assert.equal(createResponse.status(), 200);
    const {id} = await createResponse.json();
    await m.locator('[data-end="' + id + '"]').waitFor();

    await signIn(c, customer);
    assert.equal(await c.locator("#createForm").isVisible(), false);
    await c.locator('[data-claim="' + id + '"]').click();
    await c.locator("[data-tab=wallet]").click();
    const target = c.locator("#walletList .coupon").filter({hasText: cardName}).first();
    await target.locator("[data-lock]").waitFor();
    const order = "WEB-" + Date.now();
    const answers = [order, "100.00"];
    const answer = dialog => dialog.accept(answers.shift());
    c.on("dialog", answer);
    await target.locator("[data-lock]").click();
    await target.locator("[data-action=unlock]").waitFor();
    c.off("dialog", answer);
    assert.equal(await target.locator("[data-action=redeem]").count(), 0);

    await m.locator("[data-tab=redemption]").click();
    await m.locator("#redemptionForm [name=order]").fill(order);
    await merchantConfirm(m, "query", "已锁定");
    await merchantConfirm(m, "redeem", "已核销");
    await merchantConfirm(m, "refund", "可使用");
    await c.locator("#refresh").click();
    await target.locator("[data-lock]").waitFor();

    await m.locator("[data-tab=batch]").click();
    await m.locator("#batchForm [name=templateId]").fill(String(id));
    const recipient = customer.subjectId + 20;
    await m.locator("#csvFile").setInputFiles({
      name: "recipients.csv", mimeType: "text/csv",
      buffer: Buffer.from("user_id\n" + recipient + "\n" + (recipient + 1) + "\n" + recipient + "\n")
    });
    await m.locator("#batchForm button").click();
    await m.waitForFunction(() => document.querySelector("#taskId").value.length === 36);
    const taskId = await m.locator("#taskId").inputValue();
    for (let attempt = 0; attempt < 30 && !(await m.locator("#taskResult").textContent()).includes("已完成"); attempt++) {
      await m.locator("#queryTask").click();
      await m.waitForTimeout(500);
    }
    assert.match(await m.locator("#taskResult").textContent(), /成功：2/);
    assert.match(await m.locator("#taskResult").textContent(), /任务状态：已完成/);
    assert.equal(await c.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);

    await m.locator("[data-tab=templates]").click();
    await m.locator("#toast").waitFor({state: "hidden", timeout: 6000});
    const evidence = process.env.COUPON_EVIDENCE_DIR || ".local";
    fs.mkdirSync(evidence, {recursive: true});
    await m.screenshot({path: path.join(evidence, "merchant-workspace.png")});
    await m.locator("#logout").click();
    await m.locator("#login").waitFor({state: "visible"});
    assert.deepEqual(errors, []);
    const report = {result: "PASS", authenticated: true, mobileViewport: 390, templateId: id, taskId,
      checks: ["login and role UI", "authenticated async claim", "customer locks coupon",
        "merchant looks up, redeems and refunds", "CSV distribution deduplicates", "logout"]};
    fs.writeFileSync(path.join(evidence, "browser-auth-e2e.json"), JSON.stringify(report, null, 2) + "\n");
    console.log(JSON.stringify(report));
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
