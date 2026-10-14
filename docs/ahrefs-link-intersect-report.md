# StoreHop — Ahrefs Competitor Link Intersect & Prioritized Outreach Report

This report synthesizes all **11 downloaded Ahrefs Site Explorer Backlink CSV exports** across **9 grocery app competitors** — the original 5 exports (`anylist.com`, `getbring.com`, `outofmilk.com`, `ourgroceries.com`, and discontinued `groceryiq.com`) plus the **6 Round 2 exports** (`listonic.com`, discontinued `ziplist.com`, `flipp.com`, `basket.com`, and Google Play Store backlink exports for `AnyList` [`com.purplecover.anylist`] and `OurGroceries` [`com.headcode.ourgroceries`]) — into a deduplicated, prioritized Link Intersect dataset and execution plan referencing **`https://www.storehop.app/`**.

- **Master Prioritized CSV Tracker (1,081 Clean, Non-Syndicated Referring Domains)**: [`docs/ahrefs-competitor-intersect-tracker.csv`](ahrefs-competitor-intersect-tracker.csv)
- **Batch 1 Ready-to-Send Emails (First 25 Targets)**: [`docs/outreach-targets-batch-1.md`](outreach-targets-batch-1.md)
- **Master Playbook & Templates**: [`docs/ahrefs-backlink-playbook.md`](ahrefs-backlink-playbook.md)
- **CLI Outreach Sender (`info@storehop.app` via `smtp.forwardemail.net:465`)**: [`scripts/send-outreach.py`](../scripts/send-outreach.py) (`python scripts/send-outreach.py --list`)

---

## 1. Executive Summary of Your 11 Ahrefs Exports (Round 1 + Round 2)

After deduplicating domains across all 11 Ahrefs CSV exports (merging Google Play Store links for `com.purplecover.anylist` into `AnyList` and `com.headcode.ourgroceries` into `OurGroceries`) and stripping out non-editorial noise — including APK/Play Store scraper mirrors, automated podcast/transcript aggregators, recipe-card plugin widgets (`ziplist.com/webkitchen/button/add_recipe` and `*.ziplist.com/recipes/...` embeds), nutrition-trivia links (`listonic.com/p/nutrition/...`), French basketball club subdomains (`*.basket.com`), corporate career/hiring links (`corp.flipp.com`), and syndicated wire reprints (collapsing 109 local NPR station reprints into `npr.org`, 33 Scripps TV station reprints into `abc15.com`, and 10+ AP News reprints into `apnews.com`) — **1,081 clean, non-syndicated editorial & directory referring domains** remain:

| Metric / Segment | Count | Strategic Value for StoreHop (`https://www.storehop.app/`) |
| :--- | :--- | :--- |
| **Total Clean Referring Domains** | **1,081** | Deduplicated across all 11 competitor backlink exports (DR 30–94), with all 7 tiers (`Tier 1A`–`Tier 5`) accounting for 100% of rows (`69 + 87 + 1 + 50 + 44 + 395 + 435 = 1,081`). |
| **Links by Competitor in Clean Set** | **Flipp**: 451<br>**AnyList** *(Web + Play)*: 203<br>**Bring!**: 174<br>**OurGroceries** *(Web + Play)*: 132<br>**Out of Milk**: 120<br>**Listonic**: 87<br>**Grocery iQ** *(dead)*: 85<br>**ZipList** *(dead)*: 40<br>**Basket**: 29 | Adding *Flipp*, *Listonic*, *ZipList*, *Basket*, and Play Store exports more than doubled the multi-competitor intersect pool (**78 → 157 domains**) and revealed which articles cover both grocery list apps and multi-store flyer/price comparison. |
| **Multi-Competitor Domain Intersect (`Tier 1A`, `Tier 1B`, `Tier 1C`)** | **157 domains** | Sites that already link to **2 to 7** competing grocery apps (`Tier 1A`: 69, `Tier 1B`: 87, `Tier 1C`: 1). Pre-qualified to cover grocery list and multi-store shopping apps. |
| **Exact-Page Multi-Competitor Roundups (`Tier 1A`)** | **69 domains** | Single articles (`#1`–`#69`) comparing **2 to 6** grocery apps side-by-side on the exact same URL (`chowhound.com` links to 6 on one page; `cupla.app` and `amnhealthcare.com` link to 4 on one page; `lifehacker.com` links to 3 on one page and 7 across its domain). |
| **Dead App (`Grocery iQ` & `ZipList`) Link Reclamation (`Tier 2` + `Tier 1A/1B`)** | **115 domains total**<br>*(50 in `Tier 2`)* | **50** in `Tier 1A/1B` (linking to dead *Grocery iQ* and/or dead *ZipList* + live competitors) + **50** in `Tier 2` (`#158`–`#207`, core `EN`/`ES` dead-link reclamation guides) + **15** in `Tier 5`. Includes **10 "Double Dead-App" domains** linking to *both* shut-down apps! |
| **Native Multilingual (`PT` / `IT` / `ES`) Roundups (`Tier 3` + `Tier 1/2`)** | **62 domains total**<br>*(44 in `Tier 3`)* | **9** exact-page multi-competitor roundups in `Tier 1A` (`santander.pt`, `bancobpm.it`, `togetherprice.com`, `ing.it`, `remessaonline.com.br`, `elpais.com`, `hardware.com.br`, `monee-app.com`, `vaia.eu`) + **7** in `Tier 1B` + **2** in `Tier 2` + **44** dedicated guides in `Tier 3` (`#208`–`#251`). Leverages StoreHop's native **Portuguese (`pt-PT`)**, **Spanish (`es`)**, and **Italian (`it`)** localizations. |
| **Single-Competitor Topical Blogs & Guides (`Tier 4`)** | **395 domains** | High-traffic / high-DR English articles (`#252`–`#646`) on multi-store grocery savings, weekly flyer comparison, meal planning, ADHD organization, mom/family routines, and roommate expenses. |
| **High-DR Software Directory Intersect (`Tier 1C`)** | **1 domain (`f6s.com`)** | **F6S** (`#157`, DR 83) now links to **4** competitors (*AnyList*, *Bring!*, *Listonic*, and *Out of Milk*). Immediate self-serve listing opportunity. |
| **Secondary / International / General Mentions (`Tier 5`)** | **435 domains** | Non-core languages (`DE`, `FR`, `NL`, `PL`, `CS`, etc.) and incidental single-competitor mentions (`#647`–`#1081`). |

---

## 2. Tier 1A — Exact-Page Multi-Competitor Roundups (69 Domains, Ranks `#1`–`#69`)

These **69 referring domains** link to **2 to 6 competing grocery apps on the exact same article URL**. Adding the Round 2 exports upgraded `chowhound.com` (`#1`) from 4 to **6** competitors on a single page, `cupla.app` (`#2`) to **5** competitors (**4** on the same URL), `amnhealthcare.com` (`#3`) to **4** on the same URL, and `lifehacker.com` (`#4`) to **7** competitors across its domain (**3** on the same URL), while surfacing brand-new exact-page roundups like `grocerydive.com` (`#11`), `roomiapp.com` (`#24`), `mynexttablet.com` (`#27`), `remessaonline.com.br` (`#28`), `100daysofrealfood.com` (`#29`), `noozhawk.com` (`#36`), `howdoesshe.com` (`#42`), `elliott.org` (`#43`), `onehundreddollarsamonth.com` (`#46`), `meetpenny.com` (`#54`), and `ai-mealplan.com` (`#55`).

### Top 35 Actionable Tier 1A Targets (Core Languages: EN, PT, IT, ES)

| Rank | Domain | DR | Page Traffic | Lang | Competitors on Same Page (Total on Domain) | Dead App (`Grocery iQ` / `ZipList`)? | Article Title & Target URL | Author | Recommended Playbook Template & Hook |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **#1** | `chowhound.com` | 77 | 48 | `en` | **6** (*AnyList, Bring!, Flipp, Listonic, OurGroceries, OutOfMilk*) | No | [12 Apps That Make Creating Grocery Lists Much Easier](https://www.chowhound.com/1852927/grocery-list-apps/) | Marc Schenker | **Template 1** ([See Email A below](#email-a--chowhound-marc-schenker--editorial)): Links to 6 of our 9 tracked competitors on 1 page! Pitch StoreHop as #13 for shoppers splitting trips across Aldi, Costco, and Trader Joe's. |
| **#2** | `cupla.app` | 36 | 1 | `en` | **4** (*Bring!, Flipp, Listonic, OurGroceries*) (**5** w/ *AnyList*) | No | [Top Grocery List Apps for Couples: Never Forget a Thing! - Cupla](https://cupla.app/blog/the-top-grocery-list-apps-for-couples-families/) | Nick Saraev | **Template 1** ([See Email G below](#email-g--cupla-nick-saraev--couples-grocery-app-roundup)): Fellow app founder pitch — couples splitting runs between Aldi/Costco/supermarket. |
| **#3** | `amnhealthcare.com` | 76 | 0 | `en` | **4** (*Bring!, Flipp, OurGroceries, OutOfMilk*) | No | [Travel Nurse Tips: Healthy Meal Planning \| AMN Healthcare](https://www.amnhealthcare.com/blog/nursing/travel/healthy-meal-planning-tips/) | Editorial | **Template 1**: StoreHop requires no account, works offline/local-first, and adapts to new stores in every assignment city. |
| **#4** | `lifehacker.com` | 90 | 17 | `en` | **3** (*AnyList, Flipp, OurGroceries*) (**7** w/ *Basket, GroceryIQ, OutOfMilk, ZipList*) | **Yes (Both!)** | [The Best Grocery Store Apps for Every Need \| Lifehacker](https://lifehacker.com/the-best-grocery-store-apps-for-every-need-1850168630) | Lindsey Ellefson | **Template 1 + 4** ([See Email E below](#email-e--lifehacker-lindsey-ellefson--the-best-grocery-store-apps-for-every-need)): Links to 7 of 9 competitors across Lifehacker, including both dead *Grocery iQ* and dead *ZipList*! |
| **#5** | `concordia.ca` | 83 | 2 | `en` | **3** (*AnyList, OurGroceries, OutOfMilk*) (**4** w/ *Flipp*) | No | [Eating well on a budget \| News - Concordia University](https://www.concordia.ca/cunews/offices/provost/health/topics/healthy-eating/eat-well-budget.html) | Health Services | **Template 2**: Free, no-subscription multi-store grocery app for students shopping across multiple discount grocers. |
| **#6** | `everblog.com` | 35 | 0 | `en` | **3** (*AnyList, Bring!, OurGroceries*) (**4** w/ *Flipp*) | No | [A Modern Guide to the Best Grocery List App for Family Life](https://everblog.com/blogs/life-with-everblog/grocery-list-app-for-family) | Amy Ohara | **Template 1** ([See Email C below](#email-c--everblog-amy-ohara)): Direct family grocery app guide; pitch StoreHop's inviter-pays family sync + cross-store cascade. |
| **#7** | `santander.pt` | 68 | **521** | `pt` | **3** (*Bring!, Listonic, OutOfMilk*) | No | [Lista de compras: poupe tempo e dinheiro](https://www.santander.pt/salto/lista-de-compras) | Rute Ferreira | **Template 5B (PT-PT)** ([See Email K below](#email-k--santander-portugal-salto-rute-ferreira--in-european-portuguese-pt-pt)): **#1 highest-traffic Portuguese page in export (521 visits/mo)**, now confirmed linking to 3 competitors (*Bring!, Listonic, OutOfMilk*)! |
| **#8** | `bancobpm.it` | 72 | 39 | `it` | **3** (*Bring!, Listonic, OutOfMilk*) | No | [Le migliori app per fare la spesa \| Banco BPM](https://www.bancobpm.it/magazine/privati/spese-viaggi-tempo-libero/le-migliori-app-per-fare-la-spesa/) | Editorial | **Template 1 / IT**: High-DR Italian roundup of best grocery apps (*Bring!, Listonic, OutOfMilk*); pitch StoreHop's native Italian localization. |
| **#9** | `togetherprice.com` | 45 | 14 | `it, en` | **3** (*AnyList, Bring!, OurGroceries*) | No | [App Calcolo Spesa: Supermercato \| Together Price Italia](https://www.togetherprice.com/it/together-price/app-calcolo-spesa-supermercato/) | Editorial | **Template 1 / IT** ([See Email B below](#email-b--together-price-italia-italian--togetherpricecom)): Pitch StoreHop's native Italian localization + shared household multi-store sync. |
| **#10** | `scarymommy.com` | 79 | 1 | `en` | **3** (*AnyList, Flipp, OutOfMilk*) | No | [14 Best Grocery Shopping List Apps To Download Right Now](https://www.scarymommy.com/lifestyle/grocery-shopping-list-apps) | Scary Mommy Team | **Covered in Batch 1 ([Email #12](outreach-targets-batch-1.md#email-12--scary-mommy-httpswwwscarymommycomcontact--dead--paywalled-app-reclamation))** (links to *AnyList, Flipp, OutOfMilk* on the same page). |
| **#11** | `grocerydive.com` | 76 | 0 | `en` | **3** (*Listonic, OurGroceries, OutOfMilk*) | No | [Coronavirus concerns drive huge sales growth for pantry staples \| Grocery Dive](https://www.grocerydive.com/news/coronavirus-concerns-drive-huge-sales-growth-for-pantry-staples/574278/) | Megan Poinski | **Template 1**: Flagship grocery industry publication citing *Out of Milk, Listonic,* and *OurGroceries*. |
| **#13** | `cnet.com` | 91 | 1 | `en` | **2** (*OurGroceries, OutOfMilk*) (**5** w/ *AnyList, Flipp, GroceryIQ*) | **Yes (`Grocery iQ`)** | [Best shared grocery list apps to save you another trip to the store - CNET](https://www.cnet.com/tech/services-and-software/best-shared-grocery-list-apps-to-save-you-another-trip-to-the-store/) | CNET Editors | **Template 1 + 4** ([See Email D below](#email-d--cnet-best-shared-grocery-list-apps-to-save-you-another-trip-to-the-store)): Pitch StoreHop's literal "save you another trip to the store" cross-store check-off cascade. |
| **#14** | `rottenwifi.com` | 41 | 0 | `en` | **2** (*AnyList, OurGroceries*) (**4** w/ *Bring!, Flipp*) | No | [Best Shared Grocery List Apps in 2026: AnyList, OurGroceries and More](https://rottenwifi.com/best-shared-grocery-list-apps-to-save-you-another-trip-to-the-store/) | Editorial | **Template 1** ([See Email H below](#email-h--rottenwifi-2026-shared-grocery-list-apps-guide)): Active 2026 shared grocery list comparison; pitch StoreHop's cross-store cascade. |
| **#15** | `hellobrigit.com` | 55 | 216 | `en` | **2** (*AnyList, OutOfMilk*) (**3** w/ *Flipp*) | No | [How to put the brakes on impulse buying - Brigit Blog](https://www.hellobrigit.com/learn/how-to-put-the-brakes-on-impulse-buying) | Brigit Editorial | **Template 2**: High-traffic budgeting guide (216 monthly visits) recommending *AnyList* and *Out of Milk* to curb impulse grocery spending. |
| **#16** | `jackiesilvernutrition.com` | 42 | 41 | `en` | **2** (*AnyList, OutOfMilk*) (**3** w/ *Flipp*) | No | [Beginner's Guide to ADHD Meal Planning for Adults](https://jackiesilvernutrition.com/articles/adhd-meal-planning-for-adults/) | Jackie Silver | **Template 1** ([See Email L below](#email-l--jackie-silver-nutrition-adhd-meal-planning-guide)): ADHD-friendly calm UI, "Critical" / "Buy Today" flags, and zero duplicate buying across stores. |
| **#17** | `womansday.com` | 79 | 16 | `en` | **2** (*Bring!, OutOfMilk*) (**3** w/ *ZipList*) | **Yes (`ZipList`)** | [Eating Healthy on a Budget - Cheap and Healthy Meal Ideas](https://www.womansday.com/food-recipes/cooking-tips/g25903321/eating-healthy-on-a-budget/) | Karla Walsh | **Template 2 + 4**: Links to *Bring!* and *Out of Milk* on this page and dead *ZipList* on a holiday planning checklist. |
| **#18** | `moneycrashers.com` | 78 | 6 | `en` | **2** (*AnyList, OutOfMilk*) (**3** w/ *Flipp*) | No | [25 Ways to Save Money on Groceries - Food Shopping Tips on a Budget](https://www.moneycrashers.com/ways-save-money-groceries/) | Amy Freeman | **Template 2**: Frugal multi-store shopping strategy (Aldi + warehouse club + supermarket). |
| **#19** | `clark.com` | 74 | 6 | `en` | **2** (*GroceryIQ, OutOfMilk*) (**3** w/ *Flipp*) | **Yes (`Grocery iQ`)** | [Save $1,000 with the 12-week money challenge](https://clark.com/personal-finance-credit/save-1000-with-12-week-money-challenge/) | Clark Howard Team | **Template 4** ([See Email I below](#email-i--clark-howard-clarkcom--dead-grocery-iq-reclamation)): Clark Howard's frugal audience shops Aldi + Costco + Walmart; replace dead *Grocery iQ* link with StoreHop. |
| **#22** | `techpp.com` | 65 | 0 | `en` | **2** (*OurGroceries, OutOfMilk*) (**3** w/ *GroceryIQ*) | **Yes (`Grocery iQ`)** | [Awesome Shopping List Apps to Help you Never Forget Anything - TechPP](https://techpp.com/2013/04/23/shopping-list-apps-ios-android/) | Editorial | **Template 4** ([See Email F below](#email-f--techpp-dead-grocery-iq-reclamation--roundup-refresh)): Reclaim dead *Grocery iQ* link and refresh iOS/Android roundup with StoreHop. |
| **#23** | `ing.it` | 57 | 0 | `it` | **2** (*Bring!, OutOfMilk*) (**3** w/ *Listonic*) | No | [Le 10 regole d’oro del risparmio \| ING](https://www.ing.it/vocearancio/soldi/10-regole-oro-risparmiare.html) | Editorial | **Template 1 / IT**: Italian banking/savings blog linking to *Bring!, Listonic,* and *Out of Milk*. |
| **#24** | `roomiapp.com` | 51 | 0 | `en` | **2** (*Listonic, OurGroceries*) (**3** w/ *Flipp*) | No | [8 Best Apps For Roommates In 2024 \| Roomi Blog](https://roomiapp.com/blog/us/co-living/shared-apps) | Roomi Team | **Template 1** ([See Email S below](#email-s--roomi-8-best-apps-for-roommates)): Roommate co-living app roundup + grocery savings guide (`8-secrets-to-spending-less-while-grocery-shopping-roomi`). |
| **#25** | `rehack.com` | 50 | 0 | `en` | **2** (*AnyList, OurGroceries*) (**3** w/ *Flipp*) | No | [Should You Try Google Shopping List? - ReHack](https://rehack.com/tech-explained/google-shopping-list/) | Zachary Amos | **Template 1**: Compares Google Shopping List alternatives (*AnyList*, *OurGroceries*). |
| **#26** | `moneydoneright.com` | 47 | 0 | `en` | **2** (*OurGroceries, OutOfMilk*) (**3** w/ *Basket*) | No | [11 Grocery List Apps to Help You Plan Your Monthly Grocery Shopping](https://moneydoneright.com/personal-finance/saving-and-budgeting/grocery-list-apps/) | Logan Allec | **Template 1 + 2** ([See Email M below](#email-m--money-done-right-logan-allec--11-grocery-list-apps)): Links to *OurGroceries* and *Out of Milk* here + *Basket* on `how-to-save-money-on-groceries/`. |
| **#27** | `mynexttablet.com` | 31 | 36 | `en` | **2** (*AnyList, OurGroceries*) | No | [Top 10 Best Shopping List Apps For Android \| 2024 Edition • MyNextTablet](https://mynexttablet.com/best-shopping-list-app-for-android/) | Xuyun Zeng | **Template 1**: Surfaced via the Round 2 Google Play exports! Active organic traffic (36 visits/mo) ranking Android shopping list apps. |
| **#28** | `remessaonline.com.br` | 65 | 18 | `pt` | **2** (*Bring!, Listonic*) | No | [Lista de compras do supermercado: saiba como organizar e aplicativos para ajudar](https://www.remessaonline.com.br/blog/lista-de-compras-supermercado/) | Andrea Cortes | **Template 5B (PT)** ([See Email T below](#email-t--remessa-online-andrea-cortes--in-portuguese)): Upgraded to Tier 1A after Round 2 revealed it links to both *Bring!* and *Listonic* on the same URL (18 visits/mo). |
| **#29** | `100daysofrealfood.com` | 68 | 2 | `en` | **2** (*Flipp, OutOfMilk*) | No | [Four Steps for Setting up a Food Budget (+ apps to help)](https://www.100daysofrealfood.com/four-steps-setting-food-budget-apps-help/) | Lisa Leake | **Template 2** ([See Email W below](#email-w--100-days-of-real-food-lisa-leake--food-budget--apps)): Upgraded to Tier 1A — recommends both *Flipp* and *Out of Milk* on the same page for real-food families shopping Aldi + Costco + farmers markets. |
| **#30** | `elpais.com` | 91 | 0 | `es` | **2** (*Bring!, OutOfMilk*) | No | [APPS: Las mejores aplicaciones para controlar tus gastos y sobrevivir a la cuesta de enero](https://elpais.com/tecnologia/2017/01/11/actualidad/1484153801_166471.html) | Miguel Ángel Corcobado | **Template 1 / ES**: Native Spanish localization + multi-store savings. |
| **#34** | `womansworld.com` | 77 | 0 | `en` | **2** (*Basket, Flipp*) | No | [5 New Tricks to Save on Groceries During Inflation Spike \| Woman's World](https://www.womansworld.com/life/money/save-on-groceries-inflation-spike) | Julie Revelant | **Template 2**: Recommends *Basket* and *Flipp* for finding the lowest prices across local grocery stores. |
| **#36** | `noozhawk.com` | 74 | 0 | `en` | **2** (*AnyList, Flipp*) | No | [Mary Hunt: Here’s How to Save On Groceries Without Starving \| Noozhawk](https://www.noozhawk.com/mary-hunt-heres-how-to-save-on-groceries-without-starving/) | Mary Hunt | **Template 2** ([See Email Z below](#email-z--noozhawk--mary-hunt-heres-how-to-save-on-groceries-without-starving)): Syndicated Everyday Cheapskate column recommending *AnyList* + *Flipp* on the same URL. |
| **#37** | `kidsactivitiesblog.com` | 73 | 0 | `en` | **2** (*AnyList, GroceryIQ*) | **Yes (`Grocery iQ`)** | [20 Best Organizing Apps that Will Simplify Your Life](https://kidsactivitiesblog.com/73664/best-organizing-apps/) | Carla Wiking | **Template 4** ([See Email J below](#email-j--kids-activities-blog-carla-wiking--dead-grocery-iq-reclamation)): Replace discontinued *Grocery iQ* on their family organizing apps list. |
| **#42** | `howdoesshe.com` | 63 | 0 | `en` | **2** (*AnyList, ZipList*) | **Yes (`ZipList`)** | [Must-Have Apps for Moms! \| How Does She](https://howdoesshe.com/must-have-apps-for-moms/) | Editorial | **Template 4** ([See Email V below](#email-v--how-does-she-must-have-apps-for-moms--dead-ziplist-reclamation)): Links to *AnyList* and discontinued *ZipList* on the same mom app roundup! |
| **#43** | `elliott.org` | 60 | 0 | `en` | **2** (*Basket, GroceryIQ*) | **Yes (`Grocery iQ`)** | [How to save money on groceries? Timing is everything](https://www.elliott.org/blog/save-money-on-groceries-heres-how/) | Christopher Elliott | **Template 4**: Upgraded to Tier 1A after Round 2 showed it links to both *Basket* and dead *Grocery iQ* on the same URL. |
| **#46** | `onehundreddollarsamonth.com` | 52 | 0 | `en` | **2** (*GroceryIQ, ZipList*) | **Yes (Both!)** | [Must-Have Money-Saving Smartphone Apps - One Hundred Dollars a Month](https://www.onehundreddollarsamonth.com/must-have-money-saving-smartphone-apps/) | Mavis Butterfield | **Template 4** ([See Email U below](#email-u--one-hundred-dollars-a-month-mavis-butterfield--double-dead-app-reclamation)): **Double Dead-App Reclamation** — recommends *both* shut-down *Grocery iQ* and shut-down *ZipList* on the exact same page! |
| **#54** | `meetpenny.com` | 31 | 0 | `en` | **2** (*AnyList, Flipp*) | No | [12 Free Apps to Save Money on Groceries - Meet Penny](https://www.meetpenny.com/save-money-grocery-apps/) | Tabitha Philen | **Template 2**: Frugal family blog comparing *AnyList* and *Flipp* on the same page. |
| **#55** | `ai-mealplan.com` | 30 | 0 | `en` | **2** (*AnyList, Listonic*) | No | [Smart Grocery List App: What It Does and How to Choose One](https://ai-mealplan.com/blog/smart-grocery-list-app) | Editorial | **Template 1**: Dedicated smart grocery list app buyer's guide comparing *AnyList* and *Listonic*. |

---

## 3. Tier 1B & Tier 1C — Multi-Competitor Domain Intersects (87 Domains + 1 Directory, Ranks `#70`–`#157`)

These **87 domains** (plus **F6S** in `Tier 1C`) link to **2 to 6 competitors across multiple articles** on their site, proving strong editorial affinity for grocery list and grocery savings apps:

| Rank | Domain | DR | Page Traffic | Competitors Linked | Dead App (`Grocery iQ` / `ZipList`)? | Best Referring Page in Export | Author | Action / Pitch Angle |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **#70** | `apartmenttherapy.com` | 87 | 275 | **6** (*AnyList, Bring!, Flipp, GroceryIQ, OurGroceries, OutOfMilk*) | **Yes (`Grocery iQ`)** | [An Honest Review of the Out of Milk Grocery Shopping List App \| Apartment Therapy](https://www.apartmenttherapy.com/out-of-milk-app-review-36804273) (+ 5 more URLs including `best-budgeting-apps-37384460` & `money-saving-apps-266807`) | Brittney Morgan | **Already Contacted (`2026-10-14` via [Email #11](outreach-targets-batch-1.md#email-11--apartment-therapy-editorsapartmenttherapycom--dead-app-link-reclamation))**. Round 2 added `best-budgeting-apps-37384460` (275 visits/mo). |
| **#71** | `thekitchn.com` | 86 | **252** | **6** (*AnyList, Basket, Flipp, GroceryIQ, OurGroceries, OutOfMilk*) | **Yes (`Grocery iQ`)** | [5 Best Grocery List Apps of 2024 \| The Kitchn](https://www.thekitchn.com/best-grocery-list-apps-23618481) (+ 5 more URLs including `best-cheap-fruits-vegetables-aldi-walmart-lidl-258043` & `best-prices-on-grocery-list-basket-app-266310`) | Mackenzie Filson | **Already Contacted (`2026-10-14` via [Email #9](outreach-targets-batch-1.md#email-9--the-kitchn-tipsthekitchncom--dead-app-link-reclamation--roundup-pitch))**. Round 2 confirmed The Kitchn links to **6** competitors including an Aldi vs. Walmart vs. Lidl guide! |
| **#72** | `moneypantry.com` | 54 | 9 | **5** (*AnyList, Basket, Flipp, GroceryIQ, OutOfMilk*) | **Yes (`Grocery iQ`)** | [59 Best Mobile Coupon Apps to Save Money Shopping with Your (Android & iOS) Smartphone - MoneyPantry](https://moneypantry.com/best-coupon-apps/) (+ 4 more URLs) | Saeed Darabi | **Template 4**: Links to **5** competitors! Reclaim dead *Grocery iQ* link on `best-coupon-apps/` and pitch StoreHop. |
| **#73** | `today.com` | 90 | 5 | **4** (*Basket, Flipp, OutOfMilk, ZipList*) | **Yes (`ZipList`)** | [Simplify your life: Best apps and gadgets to get organized, save time](https://www.today.com/money/simplify-your-life-best-apps-gadgets-get-organized-save-time-t45906) | Julianne Pepitone | **Template 4**: Links to 4 competitors across TODAY.com including discontinued *ZipList*. |
| **#74** | `huffpost.com` | 92 | 0 | **4** (*Basket, Flipp, GroceryIQ, OutOfMilk*) | **Yes (`Grocery iQ`)** | [The 7 Best Coupon Apps Right Now \| HuffPost](https://www.huffpost.com/entry/the-7-best-coupon-apps-right-now_n_57d6fa24e4b03d2d459bb3c2) | Al Donato | **Template 4**: Links to 4 competitors including dead *Grocery iQ*. |
| **#75** | `buzzfeed.com` | 91 | 0 | **4** (*Flipp, Listonic, OurGroceries, OutOfMilk*) | No | [17 Apps That Will Make You More Efficient](https://www.buzzfeed.com/ailbhemalone/apps-that-will-make-you-more-efficient) (+ `houseshare-apps` & `grocery-store-inflation-money-saving-tips`) | Ailbhe Malone | **Template 1 + 2**: Links to 4 competitors across efficiency, roommate, and grocery inflation roundups. |
| **#76** | `thestreet.com` | 87 | 0 | **4** (*AnyList, Flipp, GroceryIQ, ZipList*) | **Yes (Both!)** | [“My Fabulous, Frugal Thanksgiving”: How to Enjoy Turkey Day on a Budget - TheStreet](https://www.thestreet.com/personal-finance/my-fabulous-frugal-thanksgiving-how-to-enjoy-turkey-day-on-a-budget-12965736) | Kristin Colella | **Template 4**: Links to **both** dead *Grocery iQ* and dead *ZipList* plus *AnyList* and *Flipp*. |
| **#77** | `wisebread.com` | 74 | 0 | **4** (*Flipp, GroceryIQ, OutOfMilk, ZipList*) | **Yes (Both!)** | [The 10 Best Couponing Apps](https://www.wisebread.com/the-10-best-couponing-apps) | Max Wong | **Template 4**: Links to **both** dead *Grocery iQ* and dead *ZipList* across 4 frugal grocery guides. |
| **#78** | `inkl.com` | 73 | 0 | **4** (*AnyList, Flipp, Listonic, OutOfMilk*) | No | [5 Grocery Apps That Do the Work for You](https://www.inkl.com/news/5-grocery-apps-that-do-the-work-for-you) | Editorial | **Template 1**: Dedicated 5-app grocery listicle + 3 more grocery savings articles. |
| **#79** | `itechguides.com` | 52 | 0 | **4** (*AnyList, Bring!, Flipp, OurGroceries*) | No | [The Best Grocery List Apps: AnyList, OurGroceries, Bring! and More](https://www.itechguides.com/the-best-grocery-list-apps-for-shared-shopping-recipes-and-simple-checklists/) | Editorial | **Template 1** ([See Email N below](#email-n--itechguides-the-best-grocery-list-apps-anylist-ourgroceries-bring-and-more)): Dedicated roundup comparing the Big 3 list apps + Flipp/Reebee. |
| **#85** | `money.com` | 84 | 0 | **3** (*Flipp, GroceryIQ, OutOfMilk*) | **Yes (`Grocery iQ`)** | [Best Apps to Save Money on Food \| Money](https://money.com/8-apps-that-will-save-you-real-money-on-food/) (+ `save-on-groceries/`) | Paul Schrodt | **Template 4**: Reclaim dead *Grocery iQ* link on `8-apps-that-will-save-you-real-money-on-food/`. |
| **#88** | `moneytalksnews.com` | 73 | 0 | **3** (*AnyList, Flipp, GroceryIQ*) | **Yes (`Grocery iQ`)** | [12 Free Money-Saving Shopping Apps](https://www.moneytalksnews.com/12-free-money-saving-shopping-apps/) | Karla Bowsher | **Template 4**: Replace dead *Grocery iQ* link on their money-saving shopping apps list. |
| **#89** | `phandroid.com` | 73 | 0 | **3** (*AnyList, Bring!, OurGroceries*) | No | [5 Best Out of Milk Alternatives for Android](https://phandroid.com/2021/06/24/5-best-out-of-milk-alternatives-for-android/) (+ `9-best-apps-for-android-smartwatch-in-2022/`) | Tyler Lee | **Template 1**: Surfaced via Round 2 Google Play export — dedicated "5 Best Out of Milk Alternatives for Android" article! |
| **#90** | `thefinancialdiet.com` | 67 | 0 | **3** (*AnyList, Flipp, OurGroceries*) | No | [5 Best Money Apps For Splitting Costs With Roommates](https://thefinancialdiet.com/5-apps-make-splitting-costs-roommates-financially-painless/) | Nicole Noel | **Template 1 + 2**: Roommates & young budgeters splitting grocery runs across Trader Joe's, Aldi, and Costco. |
| **#91** | `momtrends.com` | 52 | 0 | **3** (*Flipp, OurGroceries, ZipList*) | **Yes (`ZipList`)** | [Best Shopping List Apps To Make Life Easier - Mom Trends](https://www.momtrends.com/organization/best-shopping-list-apps-make-life-easier/) | Kate Bayless | **Template 1 + 4**: Dedicated "Best Shopping List Apps" roundup for moms + dead *ZipList* link on `recipe-organization/`. |
| **#95** | `xataka.com` | 81 | 34 | **2** (*Bring!, OurGroceries*) | No | [17 aplicaciones para organizar y gestionar la lista de la compra](https://www.xataka.com/basics/17-aplicaciones-para-organizar-gestionar-lista-compra) | Yúbal Fernández | **Template 1 / ES**: Upgraded to Tier 1B via Play Store export! Dedicated 17-app Spanish shopping list roundup (34 monthly visits). |
| **#96** | `consumer.es` | 76 | 18 | **2** (*Listonic, OutOfMilk*) | No | [Cómo hacer la lista de la compra semanal: 4 claves](https://www.consumer.es/economia-domestica/servicios-y-hogar/como-hacer-la-lista-de-la-compra-semanal-4-claves.html) | Blanca Álvarez | **Template 1 / ES**: Upgraded to Tier 1B — links to both *Listonic* and *Out of Milk* across two Spanish grocery list guides (18 monthly visits). |
| **#99** | `telemundo.com` | 77 | 3 | **2** (*Flipp, GroceryIQ*) | **Yes (`Grocery iQ`)** | [Las mejores apps de cupones para ahorrar dinero](https://www.telemundo.com/shows/un-nuevo-dia/dinero/las-mejores-apps-de-cupones-para-ahorrar-dinero-tmna2813383) | Sergio Flores | **Template 4 / ES**: Upgraded to Tier 1B — links to dead *Grocery iQ* and *Flipp* across 4 Spanish grocery savings articles. |
| **#100** | `bioguia.com` | 58 | 3 | **2** (*Listonic, OutOfMilk*) | No | [¡Adiós desorden! Conoce estas 7 apps para organizar tu tiempo de manera fácil](https://www.bioguia.com/innovacion/7-apps-para-organizar-tiempo-manera-facil_100856038.html) | Magali Navarro | **Template 1 / ES**: Upgraded to Tier 1B — links to *Listonic* and *Out of Milk*. |
| **#105** | `androidauthority.com` | 85 | 1 | **2** (*AnyList, OurGroceries*) | No | [The best grocery list apps for Android](https://www.androidauthority.com/best-grocery-list-apps-101625/) | Joe Hindy / Editorial | **Template 1**: Confirmed in Tier 1B via Round 2 Play Store export — dedicated Android roundup (`best-grocery-list-apps-101625/`) comparing *AnyList* and *OurGroceries*. |
| **#108** | `orgjunkie.com` | 54 | 1 | **2** (*GroceryIQ, ZipList*) | **Yes (Both!)** | [Grocery iQ ~ A Grocery Shopping App - I'm an Organizing Junkie](https://orgjunkie.com/2012/04/grocery-iq-a-grocery-shopping-app.html) (+ `ziplist-recipe-storage-grocery-shopping-made-easy.html`) | Laura Wittmann | **Template 4** ([See Email P below](#email-p--im-an-organizing-junkie-laura-wittmann--dead-grocery-iq--ziplist-reclamation)): Upgraded to Tier 1B — reviewed *both* discontinued *Grocery iQ* and discontinued *ZipList*! |
| **#131** | `moneysavingmom.com` | 68 | 0 | **2** (*Flipp, OurGroceries*) | No | [5 Tips to Save Time and Money on Groceries \| Money Saving Mom](https://moneysavingmom.com/5-tips-to-save-on-groceries/) | Crystal Paine | **Template 2**: Upgraded to Tier 1B — links to both *OurGroceries* and *Flipp*. |
| **#142** | `ourcal.com` | 41 | 0 | **2** (*AnyList, Bring!*) | No | [The Best Apps For Organizing Your Family \| Top Picks of 2024](https://ourcal.com/blog/the-best-apps-for-organizing-your-family) | Imi Read | **Template 1** ([See Email O below](#email-o--ourcal-imi-read--the-best-apps-for-organizing-your-family)): Indie family calendar app that recommends a "Best Family Grocery List App" — pitch StoreHop for reciprocal indie partnership! |
| **#157** | `f6s.com` *(Tier 1C)* | 83 | 0 | **4** (*AnyList, Bring!, Listonic, OutOfMilk*) | No | [AnyList Reviews and Pricing 2026 \| F6S](https://www.f6s.com/software/anylist) | Directory | **Immediate Self-Serve Directory Submission**: F6S now links to 4 competitors (*AnyList, Bring!, Listonic, OutOfMilk*). Create a StoreHop product page on F6S linking to `https://www.storehop.app/`. |

---

## 4. Tier 2 — Dedicated Dead App (`Grocery iQ` & `ZipList`) Link Reclamation (50 Domains, Ranks `#158`–`#207`)

Beyond the **50 multi-competitor domains in Tier 1A/1B** that still link to discontinued `groceryiq.com` and/or discontinued `ziplist.com` (including **10 "Double Dead-App" domains** that link to *both* dead apps: `lifehacker.com`, `thestreet.com`, `wisebread.com`, `betakit.com`, `onehundreddollarsamonth.com`, `klaw.com`, `orgjunkie.com`, `supermarketguru.com`, `goodenessgracious.com`, and `lifehacker.jp`), your `groceryiq.com` and `ziplist.com` exports surfaced **50 dedicated English & Spanish editorial articles** in Tier 2 (`#158`–`#207`) that still point readers to shut-down apps:

| Rank | Domain | DR | Dead App Linked | Article Title & Target URL | Author | Pitch Hook (`Template 4`) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **#158** | `liveabout.com` | 87 | *Grocery iQ* | [Best Coupon Apps for Grocery Shopping](https://www.liveabout.com/top-coupon-apps-939883) | Donna L. Montaldo | Flag discontinued *Grocery iQ* link (2 monthly organic visits); offer StoreHop (`https://www.storehop.app/`) as a free, ad-free multi-store list replacement. |
| **#160** | `digitaltrends.com` | 89 | *Grocery iQ* | [Apps and gadgets to organize your home - Digital Trends](https://www.digitaltrends.com/cool-tech/apps-and-gadgets-to-organize-your-home/) | Suzie Ridgeway | Replace dead *Grocery iQ* link in home organization roundup. |
| **#161** | `techrepublic.com` | 89 | *ZipList* | [Photos: 15 apps that are changing the food industry - TechRepublic](https://www.techrepublic.com/pictures/photos-15-apps-that-are-changing-the-food-industry/) | Editorial | Recommends discontinued *ZipList* for mobile grocery lists; offer StoreHop as the modern iOS/Android replacement. |
| **#164** | `softonic.com` | 86 | *ZipList* | [¿Podría una aplicación web… (II) - Softonic](https://www.softonic.com/articulos/podria-una-aplicacion-web-ii) | Abel Bueno | Spanish software authority citing *ZipList* as a supermarket task list that sorts by aisle. |
| **#168** | `fatherly.com` | 78 | *Grocery iQ* | [Best Grocery Shopping Apps](https://www.fatherly.com/gear/9-apps-that-bend-the-grocery-store-to-your-will) | Fatherly Editors | Dedicated 9-app grocery shopping roundup with broken *Grocery iQ* store links. |
| **#170** | `chatelaine.com` | 77 | *ZipList* | [Organizing apps: 10 best apps to organize your life - Chatelaine](https://chatelaine.com/living/organizing-apps-10-best-apps-to-organize-your-life/) | Chatelaine Editors | Explicitly praises *ZipList* because *"The app lets you organize by store"* — StoreHop is the exact modern multi-store replacement! |
| **#175** | `foodprint.org` | 75 | *Grocery iQ* | [Reduce Food Waste With These 15 Tips - FoodPrint](https://foodprint.org/blog/15-easy-ways-to-reduce-food-waste/) | Kristen Link | Recommends *Grocery iQ* to avoid impulse/duplicate food purchases; StoreHop's cross-store check-off directly prevents double-buying across stores. |
| **#176** | `foodtank.com` | 75 | *Grocery iQ* | [Ten Food Apps that Educate, Inspire, and Empower Eaters](https://foodtank.com/news/2014/08/ten-food-apps-to-educate-inspire-and-help-eaters/) | Philip Hanes | Dedicated 10-app food roundup linking to dead *Grocery iQ*. |
| **#177** | `redbookmag.com` | 75 | *ZipList* | [This Is How You'll Waste Less Money in 2014](https://www.redbookmag.com/life/money-career/advice/a15799/bad-money-habits/) | Maitland Greer | Recommends the dead *ZipList* app for building and storing grocery lists to stop wasting money. |
| **#178** | `whatsyourgrief.com` | 75 | *ZipList* | [Coping With Cooking After a Death - Whats your Grief](https://whatsyourgrief.com/grief-cooking-after-a-death/) | Editorial | Recommends dead `ziplist.com` to coordinate shared family grocery lists during difficult times; StoreHop's calm UI + free invitee sharing is a thoughtful replacement. |
| **#180** | `moneycheck.com` | 73 | *Grocery iQ* | [Best Coupon Apps 2020 to Save Money off Your Shopping](https://moneycheck.com/best-coupon-apps/) | Oliver Dale | Has an explicit `[Visit GroceryIQ]` CTA button pointing to the dead domain. |
| **#182** | `spafinder.com` | 71 | *ZipList* | [Take The Stress Out of Grocery Shopping: 5 Cool Apps That Make Grocery Shopping a Breeze](https://www.spafinder.com/blog/nutrition/stress-grocery-shopping-5-cool-apps-grocery-shopping-breeze/) | Editorial | Dedicated 5-app grocery shopping roundup with a broken `[Download the app now!]` link to dead *ZipList*. |
| **#184** | `skiptomylou.org` | 70 | *Grocery iQ* | [Mobile apps to make your life easier \| Skip To My Lou](https://www.skiptomylou.org/mobile-apps-to-make-your-life-easier/) | Cindy Hopper | Family/craft blog recommending dead *Grocery iQ* for grocery organization. |
| **#185** | `cooksmarts.com` | 65 | *ZipList* | [Cooking on a Budget Part 1: Planning for Savings \| Cook Smarts](https://www.cooksmarts.com/articles/planning-for-savings/) | Jess Dang | Meal-planning authority recommending dead *ZipList* for budget grocery planning. |
| **#188** | `asweatlife.com` | 62 | *ZipList* | [Meal Prep 101: How to Plan for Healthy Eating - aSweatLife](https://asweatlife.com/2014/05/meal-prep-101-how-to-plan-for-healthy-eating/) | Jeana Anderson Cohen | Healthy meal-prep guide recommending dead *ZipList* for repeat grocery buyers. |
| **#189** | `imom.com` | 62 | *Grocery iQ* | [8 New Awesome Organizational Apps for Moms - iMOM](https://www.imom.com/8-new-awesome-organizational-apps-for-moms/) | Editorial | Mom organization roundup linking to defunct *Grocery iQ*. |
| **#190** | `thebudgetnista.com` | 61 | *Grocery iQ* | [6 Steps to Buying Your Next Car in Cash - The Budgetnista](https://thebudgetnista.com/6-steps-pay-next-car-cash/) | Tiffany Aliche | Recommends *Grocery iQ* for trimming the grocery budget; offer StoreHop as the live multi-store replacement. |
| **#194** | `familytoday.com` | 54 | *ZipList* | [How To Trim Grocery Costs Using Tactical Shopping Methods - FamilyToday](https://www.familytoday.com/family/how-to-trim-grocery-costs-using-tactical-shopping-methods/) | Editorial | Specifically discusses shopping across a *"variety of stores"* and links to dead *ZipList*! |
| **#198** | `todaysmama.com` | 50 | *ZipList* | [5 Menu Planning Tools - A Review - Todays Mama](https://todaysmama.com/food/5-menu-planning-tools/) | Amy Allen Johnson | Mom menu-planning roundup linking to dead *ZipList*. |
| **#202** | `banktrack.com` | 43 | *Grocery iQ* | [Best 6 Grocery Expense Tracker Apps for 2025](https://banktrack.com/en/blog/grocery-expense-tracker-apps) | Editorial | **Recent 2025 article** still recommending discontinued *Grocery iQ*! Very high likelihood of an edit. |

---

## 5. Tier 3 — Native Multilingual (`PT` / `IT` / `ES`) Roundups (44 Domains, Ranks `#208`–`#251`)

Because **StoreHop** is natively localized in **Portuguese (`pt-PT`)**, **Spanish (`es`)**, and **Italian (`it`)**, these **44 single-competitor articles** (plus the **18 multi-competitor and dead-app `PT`/`IT`/`ES` articles** in Tiers 1A, 1B, and 2 — **62 multilingual domains total**) give you an unfair advantage over English-only competitors like *AnyList*:

| Rank | Domain | DR | Page Traffic | Lang | Competitor Linked | Article Title & Target URL | Author | Pitch Angle |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **#208** | `eldiario.es` | 85 | 1 | `es` | *AnyList* | [Tres aplicaciones para llevar siempre la lista de la compra en el móvil](https://www.eldiario.es/consumoclaro/tres-aplicaciones-llevar-lista-compra-movil_1_9838667.html) | Eric Santaona | Pitch StoreHop's native Spanish localization (unlike English-only *AnyList*) + multi-store cascade. |
| **#209** | `casafacile.it` | 58 | 1 | `it` | *Bring!* | [Come organizzare la dispensa - CasaFacile](https://www.casafacile.it/arredare/cucina/come-organizzare-la-dispensa/) | Editorial | Italian home/pantry organization guide; pitch StoreHop's native Italian app. |
| **#210** | `elle.com` | 90 | 0 | `es` | *OutOfMilk* | [16 apps para convertirte (por fin) en adulta](https://www.elle.com/es/living/ocio-cultura/news/g726885/apps-organizar-vida/) | Editorial | High-DR Spanish lifestyle app roundup; pitch StoreHop's ad-free Spanish shopping list app. |
| **#211** | `dallasnews.com` | 87 | 0 | `es, en` | *Flipp* | [Cinco consejos para hacer un presupuesto y cumplir un plan financiero](https://www.dallasnews.com/espanol/al-dia/vida-y-estilo/2022/09/01/hacer-presupuesto-finanzas-personales-consejos-plan-financiero/) | Adriana Morga | Spanish personal finance guide on Al Día (Dallas Morning News) recommending *Flipp* for supermarket deals. |
| **#212** | `eluniversal.com.mx` | 82 | 0 | `es` | *OurGroceries* | [Apps ideales para la mamás](https://www.eluniversal.com.mx/techbit/apps-ideales-para-mamas/) | TechBit | Surfaced via Round 2 Play Store export! Explicitly notes mothers compare prices across multiple supermarkets (*"conseguirse a mejor precio, en ese caso es útil usar Our Groceries"*). |
| **#214** | `observador.pt` | 79 | 0 | `pt` | *OurGroceries* | [Newsletter/ Poupar nas refeições em casa sem prejuízo da saúde](https://observador.pt/newsletters/posso-comer/poupar-nas-refeicoes-em-casa-sem-prejuizo-da-saude/) | Mariana Chaves | Surfaced via Round 2 Play Store export! Major Portuguese newspaper (`observador.pt`) recommending *Bring!* and *OurGroceries* for saving money on groceries in Portugal. |
| **#216** | `consumidormoderno.com.br` | 75 | 0 | `pt` | *AnyList* | [Aplicativos facilitam ida ao supermercado - CM](https://consumidormoderno.com.br/aplicativos-facilitam-ida-ao-supermercado-3/) | Editorial | Article explicitly notes *AnyList* is English-only — pitch StoreHop as a native Portuguese, ad-free multi-store app! |
| **#220** | `facilisimo.com` | 67 | 0 | `es` | *OurGroceries* | [Las mejores Apps para la lista de la compra - facilisimo.com](https://facilisimo.com/cocina/las-mejores-apps-para-la-lista-de-la-compra_2396695.html) | Editorial | Surfaced via Round 2 Play Store export — dedicated Spanish roundup of *"Las mejores Apps para la lista de la compra"*. |
| **#221** | `panelinha.com.br` | 67 | 0 | `pt` | *AnyList* | [Lista de compras: aplicativos de celular - Panelinha](https://panelinha.com.br/blog/alimentacaosaudavel/post/lista-de-compras-aplicativos-de-celular) | Rita Lobo / Editorial | Major culinary/food site; pitch StoreHop's native Portuguese support and multi-store list organization. |
| **#223** | `ideepercomputeredinternet.com` | 62 | 0 | `it` | *OurGroceries* | [10 App Android per la lista della spesa \| IdpCeIn](https://www.ideepercomputeredinternet.com/2019/08/lista-spesa-app-android.html) | Ernesto Tirinnanzi | Surfaced via Round 2 Play Store export! Complains that *OurGroceries* is an *"app in inglese per fare la spesa"* — pitch StoreHop as a 100% native Italian app! |
| **#225** | `androidphoria.com` | 53 | 0 | `es` | *Bring!* | [Bring, la mejor app para realizar tu lista de compras](https://androidphoria.com/aplicaciones/bring-realizar-lista-compras) | Editorial | Spanish Android app review blog; pitch StoreHop as a fast, ad-free native Kotlin/Compose alternative. |
| **#229** | `pcprofessionale.it` | 51 | 0 | `it` | *OurGroceries* | [OurGroceries per la lista della spesa - PC Professionale](https://www.pcprofessionale.it/news/app/ios/ourgroceries-per-la-lista-della-spesa/) | Dario Orlandi | Italian tech magazine; pitch StoreHop's native Italian localization and multi-store check-off. |
| **#236** | `donnaglamour.it` | 45 | 0 | `it` | *OurGroceries* | [App lista spesa, quali sono le migliori?](https://www.donnaglamour.it/app-lista-spesa/lifestyle/) | Fabrizia Volponi | Surfaced via Round 2 Play Store export — Italian lifestyle guide *"App lista spesa, quali sono le migliori?"* noting users create separate lists per supermarket. |
| **#242** | `larevista.cr` | 36 | 0 | `es` | *OurGroceries* | [17 aplicaciones para organizar y gestionar la lista de la compra - La Revista CR](https://www.larevista.cr/17-aplicaciones-para-organizar-y-gestionar-la-lista-de-la-compra/) | La Revista | Surfaced via Round 2 Play Store export — 17-app Spanish shopping list roundup. |
| **#248** | `lodehoy.com.mx` | 32 | 0 | `es` | *Listonic* | [Cómo organizar una despensa eficiente: consejos, menú y errores que debes evitar](https://lodehoy.com.mx/vida/2025/07/01/9187/como-organizar-una-despensa-eficiente-consejos-menu-y-errores-que-debes-evitar) | Editorial | Recent July 2025 Spanish pantry & grocery budget guide linking to *Listonic*. |

---

## 6. Tier 4 — Top High-Traffic Single-Competitor Guides (Top 25 of 395, Ranks `#252`–`#646`)

Adding `flipp.com`, `listonic.com`, `basket.com`, and the Google Play Store exports expanded Tier 4 from 122 to **395 high-authority English guides** (with syndicated NPR, Scripps, and AP News reprints collapsed into their canonical parent domains):

| Rank | Domain | DR | Page Traffic | Competitor Linked | Article Title & Target URL | Author | Specific Angle for StoreHop (`https://www.storehop.app/`) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **#252** | `talkwithfrida.com` | 47 | **1,526** | *OutOfMilk* | [65 Tips and Tricks to Better Manage Adult ADHD (Without Meds)](https://www.talkwithfrida.com/learn/65-tips-and-tricks-to-better-manage-adult-adhd-without-meds/) | Frida Team | **#1 highest-traffic page across all 11 exports (1,526 visits/mo)**. Pitch StoreHop's calm, zero-ad UI, "Critical" / "Buy Today" visual flags, and cross-store check-off. |
| **#253** | `garlicdelight.com` | 33 | **476** | *AnyList* | [The reverse grocery list saves time & energy when shopping](https://garlicdelight.com/reverse-shopping-list/) | Anna Rider | **Template 1 / 2** ([See Email Q below](#email-q--garlic-delight-anna-rider--the-reverse-grocery-list)): Author wrote *"I used to use AnyList and now I use Evernote"* — pitch StoreHop's master-list multi-store workflow. |
| **#254** | `nourish.com` | 71 | **400** | *Flipp* | [The Ultimate AIP Grocery List For Beginners \| Nourish](https://www.nourish.com/blog/aip-grocery-list) | Julia Zakrzewski | **New in Round 2 (400 visits/mo)**: AIP / specialty-diet shoppers must shop across multiple grocers (Whole Foods, Sprouts, Costco, Trader Joe's) and use *Flipp* for flyers — StoreHop tags AIP staples across every store in one master list! |
| **#255** | `moneymentors.ca` | 53 | **334** | *Flipp* | [Best Money Saving Apps and Websites](https://moneymentors.ca/money-tips/best-money-saving-apps-and-websites/) | Editorial | **New in Round 2 (334 visits/mo)**: Non-profit credit counseling guide recommending *Flipp*; pitch StoreHop as a 100% free, ad-free multi-store grocery list companion. |
| **#256** | `ramseysolutions.com` | 84 | **240** | *Flipp* | [Best Apps to Make Money in 2026 - Ramsey](https://www.ramseysolutions.com/saving/apps-to-make-money) | Ramsey Team | **New in Round 2 (240 visits/mo)**: Dave Ramsey's 2026 grocery savings guide recommends *Flipp* for comparing store deals; StoreHop is the zero-subscription app for executing multi-store Aldi + Costco + Walmart runs. |
| **#257** | `rent.com` | 80 | **221** | *OurGroceries* | [The Best Chore Apps for Roommates \| Rent. Blog](https://www.rent.com/blog/best-apps-for-roommates/) | Alicia Underlee Nelson | Roommates shopping at different stores (Trader Joe's vs Costco vs Target) — StoreHop's inviter-pays household sync lets roommates join free. |
| **#258** | `sproutsocial.com` | 90 | **191** | *Flipp* | [16 Best Shopping Apps of 2026 \| Sprout Social](https://sproutsocial.com/insights/shopping-apps/) | Mahnoor Sheikh | **New in Round 2 (191 visits/mo)**: Active 2026 shopping apps roundup featuring *Flipp*. |
| **#259** | `feastandwest.com` | 57 | **177** | *AnyList* | [Farmers Market Shopping Tips](https://feastandwest.com/farmers-market-shopping-tips/) (+ `8-must-have-cooking-apps/`) | Susannah Brinkley Henry | **New in Round 2 (177 visits/mo)**: Surfaced via Google Play export! Recommends *AnyList* for farmers market + supermarket shopping — StoreHop lets users tag items to both "Farmers Market" and their backup grocery store so anything not found at the market stays on the supermarket list! |
| **#260** | `androidguys.com` | 71 | **156** | *Flipp* | [5 of the Best Apps for Saving Money on Groceries – AndroidGuys](https://androidguys.com/featured/apps-for-saving-money-on-groceries/) | Editorial | **New in Round 2 (156 visits/mo)**: Dedicated 5-app Android grocery savings roundup with strong organic search traffic. |
| **#261** | `ahealthysliceoflife.com` | 44 | **113** | *Flipp* | [How to Spend Less on Groceries - A Healthy Slice of Life](https://www.ahealthysliceoflife.com/how-to-spend-less-on-groceries/) | Brittany Dixon | **New in Round 2 (113 visits/mo)**: Mom blogger sharing how she shops across Aldi, Costco, and Publix/Harris Teeter to cut her grocery bill. |
| **#262** | `neathousesweethome.com` | 30 | **86** | *AnyList* | [Amazon Echo Show 15 • Get Organized](https://www.neathousesweethome.com/amazon-echo-show-15-features-and-tips/) | Flavia Andrews | Home organization blogger; now that Amazon/Google killed 3rd-party list skills, pitch StoreHop for multi-store family organization. |
| **#263** | `berxi.com` | 52 | **63** | *AnyList* | [Best Free To-Do List Apps \| Berxi](https://www.berxi.com/resources/articles/best-free-to-do-list-apps/) | Ivo Limić | Ranks *AnyList* #4 on "Best Free To-Do List Apps" — pitch StoreHop as a truly free, no-subscription grocery list app. |
| **#265** | `moneygenius.ca` | 48 | **60** | *Flipp* | [Best Money Apps In Canada 2026 \| moneyGenius](https://moneygenius.ca/personal-finance/money-apps) | Kalleigh Lane | **New in Round 2 (60 visits/mo)**: 2026 Canadian money apps guide recommending *Flipp* for multi-store flyer shoppers. |
| **#268** | `fodmapeveryday.com` | 54 | **40** | *AnyList* | [How To Follow The Low FODMAP Diet On A Budget - FODMAP Everyday](https://www.fodmapeveryday.com/how-to-follow-the-low-fodmap-diet-on-a-budget/) | Dédé Wilson | Specialty diet shoppers *must* shop across multiple stores (Sprouts, Trader Joe's, Aldi, Walmart) to find compliant ingredients on a budget! |
| **#269** | `thesavvysparrow.com` | 48 | **33** | *OutOfMilk* | [Homemaking 101: Be a Better Homemaker with these Tips](https://thesavvysparrow.com/homemaking-101-homemaker-tips/) | Amy Williams | High-traffic homemaking & grocery organization blog (33 monthly organic visits). |
| **#280** | `foodandwine.com` | 88 | 9 | *Flipp* | [3 Free Apps That Save You Money on Grocery Shopping](https://www.foodandwine.com/apps-for-saving-money-on-groceries-12145645) | Food & Wine Editors | **New in Round 2**: Dedicated Food & Wine roundup of free grocery savings apps (*Flipp, Flashfood, Too Good To Go*). |
| **#286** | `toolfinder.com` | 62 | 7 | *Bring!* | [Best To Do List Apps for Couples in 2026 \| Productivity Tools](https://toolfinder.com/best/shared-to-do-apps-for-couples) | Francesco D'Alessio | **Template 1** ([See Email R below](#email-r--tool-finder-francesco-dalessio--best-to-do-list-apps-for-couples-in-2026)): Active 2026 couples app roundup featuring *Bring!* for shared shopping lists. |
| **#290** | `lifewire.com` | 89 | 5 | *Flipp* | [The 8 Best Mobile Shopping Apps](https://www.lifewire.com/best-mobile-shopping-apps-2378006) | Lifewire Editors | **New in Round 2 (Template 1)**: High-authority mobile shopping apps roundup featuring *Flipp*; pitch StoreHop as a free, ad-free multi-store shopping list companion. |
| **#293** | `gobankingrates.com` | 81 | 4 | *Flipp* | [6 Apps To Help You Save Money on Groceries in 2025 \| GOBankingRates](https://www.gobankingrates.com/saving-money/food/apps-help-save-money-groceries-2025/) | Editorial | **New in Round 2**: Dedicated 6-app grocery savings roundup on GOBankingRates. |
| **#312** | `npr.org` | 92 | 1 | *Flipp* | [Smart ways to save money at the grocery store : NPR](https://www.npr.org/2024/08/23/g-s1-18860/smart-ways-to-save-money-at-the-grocery-store-inflation) | Life Kit | **New in Round 2**: Canonical NPR Life Kit guide (syndicated to 109 local NPR stations in the raw export). |
| **#334** | `allrecipes.com` | 88 | 0 | *Basket* | [10 Ways to Cut Your Grocery Bill](https://www.allrecipes.com/article/ways-to-cut-your-grocery-bill/) | Allrecipes Editors | **New in Round 2**: Recommends *Basket* for comparing deals across multiple grocery stores. |
| **#367** | `eatthis.com` | 81 | 0 | *Basket* | [This App Shows You Where to Find the Cheapest Groceries — Eat This Not That](https://www.eatthis.com/app-shows-cheapest-groceries-near-you/) | Kiersten Hickman | **New in Round 2**: Dedicated multi-store grocery comparison feature on *Eat This, Not That!*. |
| **#413** | `canstarblue.com.au` | 75 | 0 | *OurGroceries* | [Grocery Shopping Apps \| 11 Apps to Help You Save – Canstar Blue](https://www.canstarblue.com.au/stores-services/grocery-shopping-list-apps/) | Dean Heckscher | **New in Round 2** ([See Email X below](#email-x--canstar-blue-dean-heckscher--grocery-shopping-list-apps)): Surfaced via Google Play export — dedicated 11-app grocery shopping list roundup. |
| **#456** | `hip2save.com` | 69 | 0 | *Flipp* | [How to Save Money on Groceries in 2025 (Our Top 13 Ideas!)](https://hip2save.com/tips/how-to-save-money-on-groceries/) | Collin Morgan / Team | **New in Round 2**: Complements our Batch 1 Hip2Save ALDI pitch ([Email #23](outreach-targets-batch-1.md#email-23--hip2save-collin-morgan--httpship2savecomcontact-us)) — recommends *Flipp* and *Amazon Alexa Shopping List* (which lost 3rd-party support). |
| **#458** | `talkandroid.com` | 69 | 0 | *OurGroceries* | [Best Android grocery list apps to help you organize shopping trips - Talk Android](https://www.talkandroid.com/guides/best-android-grocery-list-apps-to-help-you-organize-shopping-trips/) | TalkAndroid Team | **New in Round 2** ([See Email Y below](#email-y--talkandroid-best-android-grocery-list-apps)): Surfaced via Google Play export — dedicated Android grocery list app buyer's guide! |

---

## 7. Ready-to-Send Personalized Emails for Top Link Intersect Targets (`Email A` – `Email Z`)

Below are **26 ready-to-send personalized emails** (`Email A` through `Email R` from Round 1, updated with Round 2 intersect data, plus **8 brand-new emails `Email S` through `Email Z`** for the top Round 2 discoveries), complementing the 24 emails in [`docs/outreach-targets-batch-1.md`](outreach-targets-batch-1.md) (**50 total pitches** across both files). Every email is signed **`Derek`** only and references **`https://www.storehop.app/`** with zero unreplaced placeholders.

---

### Email A — Chowhound (`Marc Schenker` / Editorial)
**Contact**: `experts@static.com`
**Target URL**: `https://www.chowhound.com/1852927/grocery-list-apps/` (Links to 6 competitors on 1 page: *AnyList, Bring!, Flipp, Listonic, OurGroceries, Out of Milk*)
**Subject**: For your "12 Apps That Make Creating Grocery Lists Much Easier" roundup

```text
Hi Marc and the Chowhound Team,

I just read your roundup "12 Apps That Make Creating Grocery Lists Much Easier" (https://www.chowhound.com/1852927/grocery-list-apps/) — great comparison of AnyList, Bring!, Listonic, Flipp, OurGroceries, and Out of Milk.

One gap I noticed across those apps is that they still treat grocery stores as separate silos: if you split your shopping across Aldi, Costco, Trader Joe's, and a regular supermarket, you have to maintain separate lists for each store.

I'm an indie developer and built StoreHop (https://www.storehop.app/ — free on iOS and Android) specifically for multi-store shoppers:

- One Master List: Add an item once and tag every store where you're willing to buy it (e.g., Milk -> Aldi, Costco, Kroger).
- Automatic Cross-Store Check-Off: Buy milk at Aldi and check it off once — StoreHop automatically clears it from your Costco and Kroger views so you never double-buy.
- Per-Store Custom Aisle Order: Reorder categories independently to match each store's physical layout.
- 100% Free & Ad-Free: No ads, no account required, and no annual subscription.

If you ever update the Chowhound roundup, StoreHop would make a great pick for "Best for Multi-Store Shoppers":
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

Happy to permanently unlock StoreHop Premium (our multi-user household sync) on your account if you'd like to test it out!

Best regards,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email B — Together Price Italia (Italian — `togetherprice.com`)
**Contact**: `support@togetherprice.com`
**Target URL**: `https://www.togetherprice.com/it/together-price/app-calcolo-spesa-supermercato/` (Links to *AnyList, Bring!, OurGroceries*)
**Subject**: App gratuita per la spesa multi-supermercato (per la vostra guida sulle app per la spesa)

```text
Ciao team di Together Price,

Ho letto con molto piacere il vostro articolo sulle migliori app per fare e gestire la spesa al supermercato (https://www.togetherprice.com/it/together-price/app-calcolo-spesa-supermercato/), in cui citate AnyList, Bring!, OurGroceries e Out of Milk.

Chi cerca di risparmiare facendo la spesa in più supermercati (es. Lidl o Aldi per i prodotti base, Esselunga, Conad o Coop per il resto) si scontra sempre con lo stesso problema: dover gestire liste separate per ogni negozio o rischiare di comprare due volte lo stesso prodotto.

Ho sviluppato StoreHop (https://www.storehop.app/ — disponibile gratuitamente per iOS e Android e completamente tradotta in italiano) proprio per risolvere questo problema:

- Una sola lista principale: aggiungi un prodotto una sola volta e associ i supermercati in cui lo acquisti.
- Spunta automatica tra negozi: se compri il latte da Lidl e lo spunti, sparisce automaticamente anche dalle liste di Esselunga e Conad.
- Ordine delle corsie personalizzabile per ogni singolo supermercato.
- Gratis, senza pubblicità e senza abbonamento (la condivisione familiare multi-utente prevede un piccolo sblocco una tantum solo per chi invita, mentre tutti gli invitati entrano gratis — una filosofia di condivisione molto vicina a quella di Together Price!).

Ecco i link ufficiali se vi fa piacere aggiungerla alla vostra guida:
- Sito web: https://www.storehop.app/
- App Store (iOS): https://apps.apple.com/us/app/storehop/id6771575122
- Google Play (Android): https://play.google.com/store/apps/details?id=com.storehop.app

Se volete testare la sincronizzazione familiare, sarò felice di attivare gratuitamente StoreHop Premium sul vostro account!

Un caro saluto,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email C — Everblog (`Amy Ohara`)
**Contact**: `emily@everblog.com`
**Target URL**: `https://everblog.com/blogs/life-with-everblog/grocery-list-app-for-family` (Links to *AnyList, Bring!, OurGroceries*)
**Subject**: For your "Modern Guide to the Best Grocery List App for Family Life"

```text
Hi Amy,

I really enjoyed your article "A Modern Guide to the Best Grocery List App for Family Life" (https://everblog.com/blogs/life-with-everblog/grocery-list-app-for-family) and how you highlighted cross-platform apps like Bring!, AnyList, and OurGroceries.

For most busy families today, the biggest grocery headache isn't just sharing a list — it's that the family shops across multiple stores (like Aldi for produce/dairy, Costco for bulk staples, and Target or Walmart for everything else). Traditional apps force you to maintain separate lists for every store.

I built a free, ad-free iOS & Android app called StoreHop (https://www.storehop.app/) designed specifically for multi-store families:

1. One Master List: Tag items to every store where your family buys them.
2. Automatic Cross-Store Check-Off: When one partner checks off milk at Aldi, it automatically disappears from the Costco and Target lists too so nobody double-buys on the way home.
3. Custom Aisle Order Per Store: Sort categories independently to match each store's layout.
4. No Subscription Fatigue: Free and ad-free, with a one-time $7.99 household inviter unlock (everyone you invite joins free).

If you ever refresh your family grocery app guide, I'd love for you to take a look:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

I'd be happy to unlock permanent StoreHop Premium on your account for free if you'd like to try the family sync!

Warmly,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email D — CNET (`Best shared grocery list apps to save you another trip to the store`)
**Contact**: `cnet-corrections@cnet.com`
**Target URL**: `https://www.cnet.com/tech/services-and-software/best-shared-grocery-list-apps-to-save-you-another-trip-to-the-store/`
**Subject**: Update tip for CNET's "Best shared grocery list apps to save you another trip to the store"

```text
Hi CNET Apps & Software Team,

I was reading CNET's guide "Best shared grocery list apps to save you another trip to the store" (https://www.cnet.com/tech/services-and-software/best-shared-grocery-list-apps-to-save-you-another-trip-to-the-store/) — and noticed an older CNET piece also still links to Grocery iQ, which Coupons.com shut down.

If your team refreshes your shared grocery list roundup for 2026, I built a native iOS (SwiftUI) and Android (Kotlin/Compose) app called StoreHop (https://www.storehop.app/) around the literal premise of your headline: saving households another trip to the store when they shop across multiple grocers.

Unlike OurGroceries, AnyList, or Out of Milk — where each store list is a separate silo — StoreHop uses a single master list with multi-store tags:
- Tag milk to Aldi, Costco, and Trader Joe's once.
- Open the Aldi view in-store to see only your Aldi items in Aldi's exact aisle order.
- Check off milk at Aldi once, and StoreHop automatically clears it from your Costco and Trader Joe's views in real time across the whole household.
- Local-first, 100% ad-free, no account required for single users, and no recurring subscription (household invite creation is a one-time $7.99 unlock for the inviter only; all invitees join free).

Links for review:
- Overview: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

I'd be glad to whitelist any CNET reviewer's account for permanent free StoreHop Premium to test the real-time cross-platform household sync.

Best regards,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email E — Lifehacker (`Lindsey Ellefson` — `The Best Grocery Store Apps for Every Need`)
**Contact**: `tips@lifehacker.com`
**Target URL**: `https://lifehacker.com/the-best-grocery-store-apps-for-every-need-1850168630`
**Subject**: For Lindsey Ellefson: Multi-store addition for "The Best Grocery Store Apps for Every Need"

```text
Hi Lindsey,

I really enjoyed your Lifehacker roundup "The Best Grocery Store Apps for Every Need" (https://lifehacker.com/the-best-grocery-store-apps-for-every-need-1850168630) — and also noticed a couple of older Lifehacker grocery pieces still link to Grocery iQ and ZipList, both of which were discontinued.

There's one major "grocery need" that traditional list apps (like AnyList, OurGroceries, and Out of Milk) still don't handle well: shoppers who use Flipp to spot deals and split their routine across multiple stores (like Aldi for produce, Costco for bulk staples, and Trader Joe's or Walmart for specialty items).

I built StoreHop (https://www.storehop.app/ — free on iOS and Android) specifically for multi-store shoppers:
- Keep ONE master list and tag each item to every store where you're willing to buy it.
- Walk into Aldi and see only your Aldi items, sorted in your local Aldi's custom aisle order.
- Check off an item at Aldi once, and StoreHop's cross-store cascade automatically clears it from your Costco and Walmart views too.
- 100% ad-free, works local-first with no account required, and has zero recurring subscriptions.

If you ever update "The Best Grocery Store Apps for Every Need", StoreHop would be a natural fit for "Best for Multi-Store Shoppers":
- Website: https://www.storehop.app/
- iOS: https://apps.apple.com/us/app/storehop/id6771575122
- Android: https://play.google.com/store/apps/details?id=com.storehop.app

Happy to unlock permanent StoreHop Premium on your account if you'd like to test the household sharing feature!

Best,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email F — TechPP (`Dead Grocery iQ Reclamation + Roundup Refresh`)
**Contact**: `tips@techpp.com`
**Target URL**: `https://techpp.com/2013/04/23/shopping-list-apps-ios-android/`
**Subject**: Broken app link (Grocery iQ) on your TechPP Shopping List Apps guide

```text
Hi TechPP Editorial Team,

I was reading your roundup "Awesome Shopping List Apps to Help you Never Forget Anything" (https://techpp.com/2013/04/23/shopping-list-apps-ios-android/) alongside OurGroceries and Out of Milk, and noticed it still links to Grocery iQ — which Coupons.com permanently shut down.

If you're updating the article to replace Grocery iQ with a modern, active iOS & Android app, I built StoreHop (https://www.storehop.app/):

- Built for multi-store shoppers: maintain one master list, tag items to multiple stores (Aldi, Costco, Walmart, etc.), and check an item off once at whichever store you visit first to automatically clear it everywhere.
- Independent custom aisle ordering for every store.
- "Critical" and "Buy Today" urgency flags + product photos.
- 100% free and ad-free, with no account required and no subscription.

Here are the live links if you'd like to swap it in:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

Happy to unlock free lifetime StoreHop Premium for anyone on the TechPP team who wants to test the multi-user sync!

Best regards,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email G — Cupla (`Nick Saraev` — Couples Grocery App Roundup)
**Contact**: `hello@cupla.app`
**Target URL**: `https://cupla.app/blog/the-top-grocery-list-apps-for-couples-families/`
**Subject**: Fellow app builder + multi-store pick for Cupla's "Top Grocery List Apps for Couples"

```text
Hi Nick,

Fellow app builder here! I just read your guide "Top Grocery List Apps for Couples: Never Forget a Thing!" (https://cupla.app/blog/the-top-grocery-list-apps-for-couples-families/) — great breakdown of how OurGroceries, Listonic, Flipp, AnyList, and Bring! help couples stay in sync.

The one scenario where couples still end up double-buying groceries is when they split errands across multiple stores — e.g., one partner stops at Aldi or Trader Joe's on the way home while the other does a weekend Costco or Walmart run. In traditional apps, each store is a separate list, so checking off milk on the Aldi list leaves it sitting on the Costco list.

I built StoreHop (https://www.storehop.app/ — iOS & Android) specifically to fix that for couples and households:
- Couples keep ONE shared master list and tag items to every store where they buy them.
- When one partner checks off milk at Aldi, StoreHop's real-time sync automatically clears it from the Costco and Walmart store views on the other partner's phone.
- Each store has its own custom aisle order, plus "Critical" and "Buy Today" flags.
- No subscription fatigue: the core app is free and ad-free, and household sharing is a one-time $7.99 lifetime unlock for the inviter only (their partner joins 100% free).

Since Cupla is all about helping couples coordinate their lives and StoreHop is hyper-focused on multi-store grocery runs, would you be open to adding StoreHop to your couples grocery app guide? I'd be happy to unlock permanent StoreHop Premium for you and your team!

Best,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email H — RottenWiFi (`2026 Shared Grocery List Apps Guide`)
**Contact**: `contact@rottenwifi.com`
**Target URL**: `https://rottenwifi.com/best-shared-grocery-list-apps-to-save-you-another-trip-to-the-store/`
**Subject**: Addition for your "Best Shared Grocery List Apps in 2026" guide

```text
Hi RottenWiFi Editorial Team,

I just read your 2026 comparison "Best Shared Grocery List Apps in 2026: AnyList, OurGroceries and More" (https://rottenwifi.com/best-shared-grocery-list-apps-to-save-you-another-trip-to-the-store/).

If you're open to adding a 2026 indie contender built specifically for multi-store households, I created StoreHop (https://www.storehop.app/ — available on iOS and Android):

- Unlike AnyList, OurGroceries, and Bring! (which keep separate lists for each store), StoreHop lets you maintain one master list and tag items across multiple stores (Aldi, Costco, Trader Joe's, Walmart).
- Checking an item off at Aldi automatically removes it from your Costco and Walmart views so family members never double-buy.
- Custom per-store aisle ordering, "Critical" / "Buy Today" flags, and zero ads.
- Free with no subscription (optional one-time $7.99 inviter unlock for household sharing; all invitees join free).

Links if you'd like to check it out:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

Happy to provide a free permanent Premium unlock if you'd like to test the shared sync!

Best regards,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email I — Clark Howard (`clark.com` — Dead `Grocery iQ` Reclamation)
**Contact**: `https://clark.com/contact/`
**Target URL**: `https://clark.com/personal-finance-credit/save-1000-with-12-week-money-challenge/`
**Subject**: Dead app link (Grocery iQ) in your 12-Week Money Challenge guide + free multi-store replacement

```text
Hi Team Clark,

I was reading your guide "Save $1,000 with the 12-week money challenge" (https://clark.com/personal-finance-credit/save-1000-with-12-week-money-challenge/) and noticed the grocery savings section still links to Grocery iQ alongside Out of Milk. Since Coupons.com shut down Grocery iQ, that link now leads to a dead end.

Because Clark's readers are famous for comparison shopping across Aldi, Costco, Sam's Club, and Walmart to slash their grocery bills, I wanted to share a free, 100% ad-free app I built for iOS and Android called StoreHop (https://www.storehop.app/):

- Keep ONE master list and tag items to every store where you shop (Aldi, Costco, Walmart, Kroger, Publix).
- Walk into Aldi and see only your Aldi items, sorted in your local Aldi's exact aisle order.
- Check an item off at Aldi once, and StoreHop automatically clears it from your Costco and Walmart lists so you never accidentally double-buy.
- 100% free core app, zero ads, no account required, and no recurring subscription.

Here are the links if you'd like to replace the dead Grocery iQ link:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

Happy to unlock permanent StoreHop Premium for anyone on Team Clark who wants to test the household sharing feature!

Warm regards,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email J — Kids Activities Blog (`Carla Wiking` — Dead `Grocery iQ` Reclamation)
**Contact**: `holly@kidsactivitiesblog.com`
**Target URL**: `https://kidsactivitiesblog.com/73664/best-organizing-apps/`
**Subject**: Quick heads-up: discontinued app (Grocery iQ) on your 20 Best Organizing Apps list

```text
Hi Carla and the Kids Activities Blog Team,

I was reading your roundup "20 Best Organizing Apps that Will Simplify Your Life" (https://kidsactivitiesblog.com/73664/best-organizing-apps/) and noticed it still recommends Grocery iQ alongside AnyList. Just a friendly heads-up that Coupons.com shut down Grocery iQ, so readers clicking that link hit a dead page.

If you're updating the post for 2026, I built a calm, ad-free iOS & Android app called StoreHop (https://www.storehop.app/) designed specifically for busy parents who shop at more than one store (like Aldi + Costco + Target):

- Keep one master family grocery list and tag items to the stores where you buy them.
- Check off milk at Aldi once, and it automatically disappears from your Costco and Target lists too.
- Custom aisle ordering per store so you can get in and out with kids in tow without backtracking.
- Free, zero ads, and no monthly/annual subscription.

Here are the direct links if you'd like to swap it in where Grocery iQ used to be:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

I'd love to unlock free lifetime StoreHop Premium for you if you'd like to try the family sync!

Warmly,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email K — Santander Portugal Salto (`Rute Ferreira` — in European Portuguese `pt-PT`)
**Contact**: `https://www.santander.pt/salto`
**Target URL**: `https://www.santander.pt/salto/lista-de-compras` (521 monthly organic visits in Portugal; links to *Bring!*, *Listonic*, and *Out of Milk*)
**Subject**: Sugestão para o artigo "Lista de compras: poupe tempo e dinheiro" (App em Português de Portugal)

```text
Olá Rute e equipa editorial do Salto (Santander Portugal),

Estive a ler o vosso excelente artigo "Lista de compras: poupe tempo e dinheiro" (https://www.santander.pt/salto/lista-de-compras), onde recomendam aplicações como a Bring!, a Listonic e a Out of Milk para organizar as idas ao supermercado e evitar compras por impulso.

Em Portugal, uma das melhores formas de poupar no orçamento familiar é dividir as compras entre vários supermercados (por exemplo, aproveitar marcas próprias e padaria no Lidl ou Aldi, frescos no Pingo Doce e promoções específicas no Continente). O problema das apps tradicionais é que obrigam a manter listas separadas para cada supermercado.

Desenvolvi a StoreHop (https://www.storehop.app/ — disponível para iOS e Android e totalmente em Português de Portugal) precisamente para quem faz compras em várias lojas:

- Já traz o Continente, Pingo Doce, Lidl e Aldi pré-configurados de origem (permitindo adicionar Mercadona, Auchan ou comércio local).
- Mantém uma única lista principal: basta associar cada artigo aos supermercados onde o costuma comprar.
- Quando compra e marca um artigo no Lidl, a StoreHop remove-o automaticamente das vistas do Continente e do Pingo Doce para evitar compras duplicadas.
- Permite ordenar os corredores de forma independente para o layout de cada loja.
- 100% gratuita para uso individual, sem anúncios, sem necessidade de registo e sem subscrições mensais.

Deixo os links oficiais caso considerem útil incluir esta alternativa em Português de Portugal numa próxima atualização do artigo:
- Site oficial: https://www.storehop.app/
- App Store (iOS): https://apps.apple.com/us/app/storehop/id6771575122
- Google Play (Android): https://play.google.com/store/apps/details?id=com.storehop.app

Se quiserem testar a partilha familiar em tempo real, terei todo o gosto em ativar gratuitamente a versão Premium vitalícia nas vossas contas.

Com os melhores cumprimentos,
Derek
Criador da StoreHop
https://www.storehop.app/
```

---

### Email L — Jackie Silver Nutrition (`ADHD Meal Planning Guide`)
**Contact**: `jackie@jackiesilvernutrition.com`
**Target URL**: `https://jackiesilvernutrition.com/articles/adhd-meal-planning-for-adults/` (Links to *AnyList, OutOfMilk* + *Flipp*)
**Subject**: Calm, ADHD-friendly multi-store grocery app for your ADHD Meal Planning guide

```text
Hi Jackie,

I really appreciated your "Beginner's Guide to ADHD Meal Planning for Adults" (https://jackiesilvernutrition.com/articles/adhd-meal-planning-for-adults/), especially your tip on keeping a categorized digital list on your phone with apps like AnyList or Out of Milk.

One executive-function hurdle we kept running into with traditional grocery apps is what happens when you shop at more than one store (like Aldi, Costco, and a local grocer):
1. Many free apps (like Out of Milk) are now packed with flashing banner ads and pop-ups that cause sensory overload in the aisle.
2. Keeping separate lists per store means you inevitably forget to cross off an item on Store B's list after buying it at Store A — leading to accidental double-buying.

I built a calm, 100% ad-free app for iOS and Android called StoreHop (https://www.storehop.app/) that reduces cognitive load in the store:
- One Master List: Tag items to the stores where you buy them once.
- Automatic Cross-Store Check-Off: Check off milk at Store A, and it disappears from Store B and C automatically.
- Custom Aisle Order + Visual Urgency Flags: Mark items as "Critical" or "Buy Today" so when overwhelm hits, you can filter down to only what you truly need right now, ordered by the exact aisles of that store.
- Free, zero ads, no account required, and no subscription.

If you ever update your ADHD Meal Planning guide, I'd love for you to check it out:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

I'd be happy to unlock permanent StoreHop Premium on your account for free if you'd like to test the shared household feature!

Warmly,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email M — Money Done Right (`Logan Allec` — `11 Grocery List Apps`)
**Contact**: `support@moneydoneright.com`
**Target URL**: `https://moneydoneright.com/personal-finance/saving-and-budgeting/grocery-list-apps/` (Links to *OurGroceries, OutOfMilk* + *Basket*)
**Subject**: Addition for your "11 Grocery List Apps to Help You Plan Your Monthly Grocery Shopping"

```text
Hi Logan and the Money Done Right Team,

I just read your guide "11 Grocery List Apps to Help You Plan Your Monthly Grocery Shopping" (https://moneydoneright.com/personal-finance/saving-and-budgeting/grocery-list-apps/).

Anyone serious about cutting their monthly grocery budget quickly realizes they can't buy everything at one store — they buy bulk staples at Costco or Sam's Club, produce and dairy at Aldi, and remaining items at Walmart or Kroger. Yet almost every app on the market forces you to maintain separate, disconnected lists for each store.

I built a free, ad-free iOS & Android app called StoreHop (https://www.storehop.app/) designed specifically for multi-store budget shoppers:
- Keep ONE master list and tag each item to the stores where you buy it.
- Walk into Aldi and see only your Aldi items, sorted by your local Aldi's aisle order.
- Check an item off at Aldi once, and StoreHop automatically removes it from your Costco and Walmart lists so you never waste money buying duplicates.
- 100% free core app, zero ads, no account required, and no monthly/annual subscription.

If you ever refresh your Grocery List Apps roundup, here are the links:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

Happy to unlock permanent StoreHop Premium on your account if you'd like to test the household sync!

Best regards,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email N — iTechGuides (`The Best Grocery List Apps: AnyList, OurGroceries, Bring! and More`)
**Contact**: `https://www.itechguides.com/contact/`
**Target URL**: `https://www.itechguides.com/the-best-grocery-list-apps-for-shared-shopping-recipes-and-simple-checklists/`
**Subject**: Multi-store contender for your "Best Grocery List Apps" guide on iTechGuides

```text
Hi iTechGuides Editorial Team,

I really enjoyed your comparison "The Best Grocery List Apps: AnyList, OurGroceries, Bring! and More" (https://www.itechguides.com/the-best-grocery-list-apps-for-shared-shopping-recipes-and-simple-checklists/) — as well as your coverage of Amazon Alexa dropping its third-party shopping list integration.

Now that voice-assistant integrations are gone and many users are re-evaluating AnyList's annual subscription, I wanted to share a native iOS & Android app I built called StoreHop (https://www.storehop.app/) that solves a problem none of the Big 3 handle natively: multi-store grocery shopping.

In AnyList, OurGroceries, and Bring!, each store list is a separate silo. In StoreHop:
- You maintain ONE master list and tag items to every store where you're willing to buy them (e.g., Aldi, Costco, Trader Joe's, Walmart).
- When you walk into Aldi, you filter to your Aldi view — sorted in your local Aldi's custom aisle order.
- When you check off milk at Aldi, StoreHop's cross-store cascade automatically clears it from your Costco and Walmart views too.
- 100% ad-free, local-first (no account required), and no recurring subscription (optional household invite creation is a one-time $7.99 unlock for the inviter only; all invitees join free).

Links if you'd like to review it for your roundup:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

Happy to unlock permanent StoreHop Premium on any reviewer's account!

Best regards,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email O — OurCal (`Imi Read` — `The Best Apps For Organizing Your Family`)
**Contact**: `hi@ourcal.com`
**Target URL**: `https://ourcal.com/blog/the-best-apps-for-organizing-your-family`
**Subject**: Indie builder hello + family grocery app for your OurCal Family Apps guide

```text
Hi Imi,

Fellow indie app builder here! I loved your roundup "The Best Apps For Organizing Your Family" (https://ourcal.com/blog/the-best-apps-for-organizing-your-family) — and really admire how OurCal focuses on privacy-first shared family calendars without bloated clutter.

We took that exact same calm, privacy-first philosophy and applied it to the one thing families do 2–3 times a week: multi-store grocery shopping.

I built StoreHop (https://www.storehop.app/ — iOS & Android) for families who split their grocery runs across multiple stores (like Aldi, Costco, Trader Joe's, and Tesco/Walmart):
- Families keep ONE master grocery list and tag items to the stores where they buy them.
- When one family member buys milk at Aldi and checks it off, StoreHop automatically removes it from the Costco and supermarket views in real time so nobody double-buys.
- Custom per-store aisle ordering, zero ads, no data tracking, and no recurring subscription (one-time $7.99 unlock for the family inviter; everyone they invite joins free).

Since OurCal is the go-to for shared family calendars and StoreHop is hyper-focused on shared multi-store grocery lists, would you be open to featuring StoreHop in your family organization guides? I'd be thrilled to unlock permanent StoreHop Premium for the OurCal team and cross-promote OurCal to families asking us for a shared calendar!

Best,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email P — I'm an Organizing Junkie (`Laura Wittmann` — Dead `Grocery iQ` & `ZipList` Reclamation)
**Contact**: `organizingjunkie@gmail.com`
**Target URL**: `https://orgjunkie.com/2012/04/grocery-iq-a-grocery-shopping-app.html`
**Subject**: Successor to Grocery iQ & ZipList for your Organizing Junkie readers

```text
Hi Laura,

I came across your reviews of the Grocery iQ app (https://orgjunkie.com/2012/04/grocery-iq-a-grocery-shopping-app.html) and ZipList (https://orgjunkie.com/2011/09/ziplist-recipe-storage-grocery-shopping-made-easy.html) on I'm an Organizing Junkie, where you highlighted two features that organized shoppers loved: assigning items to multiple stores and customizing the aisle order per store.

Since Coupons.com shut down Grocery iQ and Condé Nast shut down ZipList, it's been surprisingly hard to find a clean, ad-free app that actually handles multiple stores properly without charging an annual subscription.

I built a free iOS & Android app called StoreHop (https://www.storehop.app/) around that exact workflow:
- Keep one master grocery list and tag each item to every store where you buy it (Aldi, Costco, Walmart, etc.).
- Customize the aisle/category order independently for each store so you never backtrack.
- Check an item off at whichever store you visit first, and StoreHop automatically clears it from every other store's list.
- 100% ad-free, no account required, and no subscription.

If you ever add an update note to your Grocery iQ or ZipList posts or share new organizing tools with your readers, here are the links:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

I'd be happy to unlock permanent StoreHop Premium on your account for free if you'd like to test the shared household feature!

Warmly,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email Q — Garlic Delight (`Anna Rider` — `The reverse grocery list`)
**Contact**: `anna@garlicdelight.com`
**Target URL**: `https://garlicdelight.com/reverse-shopping-list/` (476 monthly organic visits)
**Subject**: Your "reverse grocery list" post (+ why you left AnyList)

```text
Hi Anna,

I loved reading your guide on "The reverse grocery list saves time & energy when shopping" (https://garlicdelight.com/reverse-shopping-list/) — especially your note that you used to use AnyList before moving to Evernote/OneNote for a master template workflow.

The exact reason I stopped using traditional grocery apps is that they treat every store as a separate silo, instead of letting you maintain one master staple list that adapts to whichever store you're standing in.

I ended up building a free, calm, ad-free app for iOS and Android called StoreHop (https://www.storehop.app/) that works just like a smart reverse grocery list across multiple stores:
- You maintain one master list of items, tagged to the stores where you buy them (e.g., Trader Joe's, Costco, Whole Foods, Asian market) and marked with urgency ("Critical" / "Buy Today") when you run low.
- When you walk into Trader Joe's, you tap the Trader Joe's filter and see only what you currently need that TJ's carries, ordered by TJ's aisle layout.
- Check an item off at Trader Joe's, and it automatically clears from your Costco and Whole Foods views too — while staying in your history for one-tap re-adding next week.
- Local-first, zero ads, no account required, and no subscription ($0 core; optional CSV export/import + household invite creation is a one-time $7.99 unlock).

Given how thoughtfully you design your kitchen workflows, I'd love to hear what you think of StoreHop — and I'd be happy to unlock permanent StoreHop Premium on your account so you can try the CSV import/export with your master staple list!

Best,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email R — Tool Finder (`Francesco D'Alessio` — `Best To Do List Apps for Couples in 2026`)
**Contact**: `tools@keepproductive.com`
**Target URL**: `https://toolfinder.com/best/shared-to-do-apps-for-couples`
**Subject**: For Francesco: Multi-store shared grocery app for Tool Finder's couples & grocery guides

```text
Hi Francesco,

Big fan of your app reviews on Tool Finder and Keep Productive! I just saw your 2026 roundup "Best To Do List Apps for Couples in 2026" (https://toolfinder.com/best/shared-to-do-apps-for-couples) where you included Bring! for shared household shopping lists.

I'm an indie developer and built StoreHop (https://www.storehop.app/ — native SwiftUI on iOS & Jetpack Compose on Android) to solve the one friction point that Bring!, AnyList, and OurGroceries all miss for couples: multi-store grocery shopping.

Instead of keeping separate lists for Aldi, Costco, Trader Joe's, and Tesco/Walmart, couples in StoreHop keep ONE master list and tag each item to the stores where they're willing to buy it:
- When one partner stops at Aldi and checks off milk, StoreHop's real-time sync automatically clears milk from the Costco and supermarket views too — preventing double-buying.
- Each store has its own independent aisle order, plus "Critical" and "Buy Today" flags.
- Zero ads, local-first, and no recurring subscription: the core app is free, and household sharing uses an "inviter-pays" one-time $7.99 lifetime unlock (the partner joins 100% free).

If you ever review grocery list apps on Tool Finder or update your 2026 couples roundup, I'd love for you to take StoreHop for a spin — just reply with the Google email you use in the app and I'll permanently whitelist your account for StoreHop Premium!

Best regards,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email S — Roomi (`8 Best Apps For Roommates`)
**Contact**: `hello@roomiapp.com`
**Target URL**: `https://roomiapp.com/blog/us/co-living/shared-apps` (Links to *Listonic, OurGroceries* + *Flipp*)
**Subject**: Shared multi-store grocery app for Roomi's "Best Apps For Roommates" guide

```text
Hi Roomi Editorial Team,

I just read your roommate guides "8 Best Apps For Roommates" (https://roomiapp.com/blog/us/co-living/shared-apps) and "8 Secrets to Spending Less While Grocery Shopping" (https://roomiapp.com/blog/us/finance/8-secrets-to-spending-less-while-grocery-shopping-roomi), where you recommend OurGroceries, Listonic, and Flipp.

One problem roommates always run into with traditional grocery apps is that different roommates shop at different stores — one person grabs produce at Trader Joe's or Aldi, another does a Costco run, and someone else stops at Target or Walmart. In older apps, each store is a separate list, so roommates end up buying two cartons of milk on the same day.

I built a free, ad-free iOS & Android app called StoreHop (https://www.storehop.app/) designed specifically for shared households and roommates who shop across multiple stores:
- One Shared Master List: Tag shared staples (milk, eggs, paper towels, coffee) to every store where the apartment buys them.
- Real-Time Cross-Store Check-Off: When one roommate checks off paper towels at Target, StoreHop automatically clears them from the Costco and Trader Joe's views on every roommate's phone.
- Inviter-Pays Roommate Sync: The core app is 100% free with zero ads, and shared household sync is a one-time $7.99 unlock for the inviter only — every roommate they invite joins 100% free (no monthly subscription).

If you ever update your Roomi app roundups, here are the links:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

Happy to unlock permanent StoreHop Premium for anyone on the Roomi team who wants to test it out!

Best,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email T — Remessa Online (`Andrea Cortes` — in Portuguese)
**Contact**: `imprensa@remessaonline.com.br`
**Target URL**: `https://www.remessaonline.com.br/blog/lista-de-compras-supermercado/` (Links to *Bring!* and *Listonic*)
**Subject**: Sugestão para o artigo "Lista de compras do supermercado" (App em Português)

```text
Olá Andrea e equipa de conteúdo da Remessa Online,

Li o vosso artigo "Lista de compras do supermercado: saiba como organizar e aplicativos para ajudar" (https://www.remessaonline.com.br/blog/lista-de-compras-supermercado/), onde recomendam o Bring! e o Listonic para organizar as compras e poupar tempo e dinheiro.

Para quem vive no exterior (como em Portugal, na Europa ou nos EUA) ou divide as compras da casa entre vários supermercados e atacadistas (por exemplo, Lidl, Aldi, Continente, Pingo Doce ou Costco), as apps tradicionais têm uma limitação: obrigam a criar listas separadas para cada loja.

Desenvolvi a StoreHop (https://www.storehop.app/ — gratuita para iOS e Android e totalmente em Português) especificamente para quem faz compras em mais do que um supermercado:
- Lista Principal Única: adicione cada produto uma única vez e associe as lojas onde o costuma comprar.
- Baixa Automática Entre Lojas: quando compra e marca o leite no Lidl, ele desaparece automaticamente das listas do Continente e do Pingo Doce, evitando compras repetidas.
- Ordem de Corredores por Loja: organize as categorias de acordo com o layout físico de cada supermercado.
- Sem Anúncios e Sem Mensalidades: 100% gratuita para uso individual, sem anúncios e sem necessidade de criar conta.

Deixo os links oficiais caso queiram incluir a StoreHop numa futura atualização do guia:
- Site oficial: https://www.storehop.app/
- App Store (iOS): https://apps.apple.com/us/app/storehop/id6771575122
- Google Play (Android): https://play.google.com/store/apps/details?id=com.storehop.app

Se quiserem testar a sincronização familiar em tempo real, terei todo o gosto em liberar gratuitamente o acesso Premium vitalício para a vossa equipa!

Um abraço,
Derek
Criador da StoreHop
https://www.storehop.app/
```

---

### Email U — One Hundred Dollars a Month (`Mavis Butterfield` — Double Dead-App Reclamation)
**Contact**: `onehundreddollarsamonth@gmail.com`
**Target URL**: `https://www.onehundreddollarsamonth.com/must-have-money-saving-smartphone-apps/` (Links to dead *Grocery iQ* and dead *ZipList*)
**Subject**: Heads-up: both Grocery iQ and ZipList shut down (on your Money-Saving Apps post)

```text
Hi Mavis,

Longtime admirer of One Hundred Dollars a Month! I was reading your guide "Must-Have Money-Saving Smartphone Apps" (https://www.onehundreddollarsamonth.com/must-have-money-saving-smartphone-apps/) and noticed the grocery section recommends both Grocery iQ and ZipList.

Just a friendly heads-up that Coupons.com shut down Grocery iQ and Condé Nast shut down ZipList, so both of those links now lead to dead ends.

Because frugal shoppers almost always split their grocery runs across multiple stores (like Costco for bulk staples, Aldi or Grocery Outlet for produce and dairy, and a local supermarket for loss leaders), I built a free, 100% ad-free app for iOS and Android called StoreHop (https://www.storehop.app/) to replace them:

- Keep ONE master grocery list and tag items to every store where you shop (Costco, Aldi, Trader Joe's, Walmart, Kroger).
- Walk into Aldi and see only your Aldi items, ordered by your local Aldi's aisles.
- Check an item off at Aldi once, and StoreHop automatically clears it from your Costco and supermarket lists so you never accidentally double-buy.
- 100% free core app, zero ads, no account required, and no monthly or annual subscription.

If you ever update your money-saving apps post, here are the links if you'd like to swap StoreHop in:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

I'd be happy to unlock permanent StoreHop Premium on your account for free if you'd like to try the shared household feature!

Warmly,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email V — How Does She (`Must-Have Apps for Moms!` — Dead `ZipList` Reclamation)
**Contact**: `help@howdoesshe.com`
**Target URL**: `https://howdoesshe.com/must-have-apps-for-moms/` (Links to *AnyList* and dead *ZipList*)
**Subject**: Quick fix for your "Must-Have Apps for Moms!" post (ZipList shut down)

```text
Hi HowDoesShe Team,

I was reading your roundup "Must-Have Apps for Moms!" (https://howdoesshe.com/must-have-apps-for-moms/) where you recommend AnyList and ZipList for grocery shopping and meal planning. Just wanted to give you a quick heads-up that ZipList was permanently shut down, so that link no longer works.

If you're updating the post with a live, mom-friendly replacement, I built a calm, 100% ad-free app for iOS and Android called StoreHop (https://www.storehop.app/) designed for moms who shop across more than one store (like Costco + Aldi + Target or Walmart):

- One Master List: Tag items to every store where your family buys them instead of juggling separate lists for Costco, Aldi, and Target.
- Automatic Cross-Store Check-Off: Check off milk or berries at Costco once, and StoreHop automatically clears them from your Aldi and Target lists so nobody buys duplicates.
- Custom Aisle Order Per Store: Sort categories independently to match each store's layout.
- Free, zero ads, and no subscription (optional one-time $7.99 unlock for the family inviter; spouses and kids join 100% free).

Here are the links if you'd like to replace the broken ZipList entry:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

Happy to unlock free lifetime StoreHop Premium for anyone on the HowDoesShe team!

Warm regards,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email W — 100 Days of Real Food (`Lisa Leake` — `Food Budget + Apps`)
**Contact**: `lisa@100daysofrealfood.com`
**Target URL**: `https://www.100daysofrealfood.com/four-steps-setting-food-budget-apps-help/` (Links to *Flipp* and *Out of Milk*)
**Subject**: Multi-store grocery app for your "Four Steps for Setting up a Food Budget" guide

```text
Hi Lisa,

I really enjoyed your guide "Four Steps for Setting up a Food Budget (+ apps to help)" (https://www.100daysofrealfood.com/four-steps-setting-food-budget-apps-help/), where you highlight Flipp and Out of Milk.

As you know better than anyone, buying real, whole foods on a budget almost always means shopping across multiple places — like Aldi for organic produce and dairy, Costco for bulk nuts/oils/meats, a local farmers market, and a regular supermarket. The challenge with traditional list apps is that they force you to keep separate, disconnected lists for every store.

I built a free, 100% ad-free app for iOS and Android called StoreHop (https://www.storehop.app/) designed specifically for multi-store real-food shoppers:
- Keep ONE master list and tag each ingredient to every store (or farmers market!) where you're willing to buy it.
- When you're at the farmers market or Aldi, filter to that store's view and check off what you find — StoreHop automatically clears those items from your Costco and supermarket lists so you only buy what's still left.
- Custom aisle ordering per store, "Critical" and "Buy Today" flags, and zero flashing ads or subscriptions.

If you ever update your Food Budget app recommendations, I'd love for you to take a look:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

I'd be happy to unlock permanent StoreHop Premium on your account for free if you'd like to test the family sync!

Warmly,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email X — Canstar Blue (`Dean Heckscher` — `Grocery Shopping List Apps`)
**Contact**: `enquiries@canstarblue.com.au`
**Target URL**: `https://www.canstarblue.com.au/stores-services/grocery-shopping-list-apps/` (Links to *OurGroceries*)
**Subject**: Addition for Canstar Blue's "Grocery Shopping Apps | 11 Apps to Help You Save"

```text
Hi Dean and the Canstar Blue Editorial Team,

I was reading your guide "Grocery Shopping Apps | 11 Apps to Help You Save" (https://www.canstarblue.com.au/stores-services/grocery-shopping-list-apps/), where you review OurGroceries alongside Aussie supermarket apps.

With so many shoppers splitting their weekly grocery run across ALDI (for Special Buys and staples), Costco, Coles, and Woolworths to beat rising food costs, traditional list apps like OurGroceries still have one big catch: you have to maintain separate lists for each supermarket.

I built a free, 100% ad-free iOS and Android app called StoreHop (https://www.storehop.app/) designed specifically for multi-store shoppers:
- One Master List: Add an item once and tag every store where you buy it (e.g., ALDI, Costco, Coles, Woolworths).
- Automatic Cross-Store Check-Off: Buy milk and produce at ALDI first and check them off once — StoreHop automatically clears them from your Coles and Woolworths views so you never double-buy.
- Independent Aisle Order Per Store: Customise category order for each store's physical layout.
- 100% Ad-Free & No Subscription: Free core app with no ads and no login required.

If you refresh your Canstar Blue grocery shopping apps guide, here are the links for review:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

Happy to whitelist any Canstar Blue reviewer's account for permanent free StoreHop Premium to test the real-time household sync!

Best regards,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email Y — TalkAndroid (`Best Android grocery list apps`)
**Contact**: `tips@talkandroid.com`
**Target URL**: `https://www.talkandroid.com/guides/best-android-grocery-list-apps-to-help-you-organize-shopping-trips/` (Links to *OurGroceries* on Google Play)
**Subject**: Native Jetpack Compose multi-store grocery app for your TalkAndroid Grocery List Apps guide

```text
Hi TalkAndroid Team,

I just read your buyer's guide "Best Android grocery list apps to help you organize shopping trips" (https://www.talkandroid.com/guides/best-android-grocery-list-apps-to-help-you-organize-shopping-trips/), where you highlight OurGroceries.

If you're updating that roundup for 2026, I built a modern, native Android (Kotlin + Jetpack Compose + Material 3) and iOS grocery app called StoreHop (https://www.storehop.app/) that solves a problem legacy Android list apps don't handle natively: multi-store shopping runs.

Instead of keeping separate siloed lists for Aldi, Costco, Trader Joe's, and Walmart:
- StoreHop lets you keep ONE master list and tag each item to every store where you're willing to buy it.
- Tap the Aldi chip when you walk into Aldi to see only your Aldi items, sorted in your local Aldi's custom aisle order.
- Check off milk at Aldi once, and StoreHop's cross-store cascade automatically clears it from your Costco and Walmart views too.
- Local-first (Room SQLite + optional real-time Cloud Firestore household sync), 100% ad-free, no account required, and zero recurring subscriptions.

Links for review:
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122

I'd be happy to permanently whitelist any TalkAndroid reviewer's Google account for StoreHop Premium so you can test the real-time household sync and CSV import/export!

Best,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

### Email Z — Noozhawk / Mary Hunt (`Here's How to Save On Groceries Without Starving`)
**Contact**: `news@noozhawk.com`
**Target URL**: `https://www.noozhawk.com/mary-hunt-heres-how-to-save-on-groceries-without-starving/` (Links to *AnyList* and *Flipp*)
**Subject**: Note on "Here's How to Save On Groceries Without Starving" (AnyList + Flipp companion)

```text
Hi Noozhawk Lifestyle Editors,

I enjoyed reading the column "Mary Hunt: Here's How to Save On Groceries Without Starving" on Noozhawk (https://www.noozhawk.com/mary-hunt-heres-how-to-save-on-groceries-without-starving/), which recommends using Flipp to spot weekly store sales and AnyList to organize your shopping list.

For readers who check weekly flyers and split their shopping across multiple stores (like Costco, Trader Joe's, Sprouts, Vons, or Aldi), traditional list apps like AnyList still require you to keep separate lists for each store — and now charge an annual subscription for full features.

I built a free, 100% ad-free iOS & Android app called StoreHop (https://www.storehop.app/) specifically for multi-store grocery savers:
- Keep ONE master list and tag items to every store where you shop.
- Walk into Trader Joe's or Aldi and see only that store's items, sorted by your custom aisle order for that store.
- Check an item off at Store A once, and StoreHop automatically clears it from Store B and Store C so you never accidentally double-buy.
- 100% free core app, zero ads, no account required, and no annual subscription.

If you share follow-up reader tips or update your grocery savings resources, here are the links:
- Website: https://www.storehop.app/
- iOS App Store: https://apps.apple.com/us/app/storehop/id6771575122
- Google Play: https://play.google.com/store/apps/details?id=com.storehop.app

Happy to unlock permanent StoreHop Premium for anyone in the newsroom who'd like to test the shared household feature!

Best regards,
Derek
Creator of StoreHop
https://www.storehop.app/
```

---

## 8. How to Use the CSV Tracker (`docs/ahrefs-competitor-intersect-tracker.csv`)

1. Open [`docs/ahrefs-competitor-intersect-tracker.csv`](ahrefs-competitor-intersect-tracker.csv) in Excel, Google Sheets, or Notion.
2. Filter by **`Outreach_Tier`**:
   - Start with **`Tier 1A: Exact-Page Multi-Competitor Roundup`** (Rows `#1`–`#69`) and **`Tier 1B: Multi-Competitor Domain Intersect`** (Rows `#70`–`#156`). Note that `apartmenttherapy.com` (`#70`) and `thekitchn.com` (`#71`) are already marked `Contacted (2026-10-14)`.
   - Submit StoreHop on **F6S** (`Row #157`, `Tier 1C`, linking to 4 competitors) alongside your Tier 1 directories in [`docs/ahrefs-backlink-playbook.md`](ahrefs-backlink-playbook.md).
   - Work through **`Tier 2: Dead App (Grocery iQ / ZipList) Link Reclamation`** (Rows `#158`–`#207`, plus the 50 dead-app rows in Tier 1A/1B including the 10 "Double Dead-App" targets) using **Template 4** — editors love fixing broken links to shut-down apps.
   - Send localized pitches to **`Tier 3: Native Multilingual (PT / IT / ES) Roundup`** (Rows `#208`–`#251`) + the 18 multi-competitor `PT`/`IT`/`ES` targets in Tiers 1A, 1B, and 2 (`santander.pt` `#7`, `bancobpm.it` `#8`, `togetherprice.com` `#9`, `ing.it` `#23`, `remessaonline.com.br` `#28`, `elpais.com` `#30`, `hardware.com.br` `#44`, `monee-app.com` `#49`, `vaia.eu` `#50`, `xataka.com` `#95`, `consumer.es` `#96`, `telemundo.com` `#99`, `bioguia.com` `#100`, `gestion.pe` `#123`, `wwwhatsnew.com` `#130`, `softdownload.com.br` `#143`, `softonic.com` `#164`, and `marketingdirecto.com` `#171`).
   - Continue with **`Tier 4: Single-Competitor Topical Blog / Guide`** (Rows `#252`–`#646`), sorted by organic `Page_Traffic` and `DR` (with syndicated NPR, Scripps, and AP News reprints already collapsed into their canonical parent domains).
   - Optionally review **`Tier 5: Secondary / International / General Mention`** (Rows `#647`–`#1081`) for non-core language (`DE`, `FR`, `NL`, `PL`) or secondary mentions.
3. Update the **`Status`** column (`Not Contacted` → `Contacted` → `Followed Up` → `Link Live`) as you send 5 emails per day via `python scripts/send-outreach.py --send --limit 5`.
