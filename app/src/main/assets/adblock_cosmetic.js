/* Mind Browser 页面广告清理（化妆过滤）
 *
 * 两层思路：
 * 1. 已知广告容器选择器直接隐藏（百度推广、必应广告位等）
 * 2. “广告”角标启发式：国内法规要求广告必须标注“广告”二字，
 *    找到叶子级的小角标后，隐藏它所在的整条结果容器。
 *    角标样式会变，但“广告”这两个字法律规定必须在，所以这条很稳。
 */
(function () {
    if (window.__mindAdClean) return;
    window.__mindAdClean = true;

    var AD_SELECTORS = [
        '.ec_ad',
        '.ec_wise_ad',
        '[data-ad]',
        '[data-adid]',
        '.c-result[tpl^="ad"]',
        'li.b_ad',
        '.b_ad',
        '[class*="sponsor"]'
    ];

    /* 各搜索引擎一条“结果”的容器：百度 c-container/c-result/result、必应 b_algo 等 */
    var CONTAINER_SELECTOR =
        '.c-container, .c-result, .result, .vrwrap, [data-log], li.b_algo, li.b_ad, .res-list, .g';

    function hideBySelector() {
        AD_SELECTORS.forEach(function (sel) {
            try {
                document.querySelectorAll(sel).forEach(function (el) {
                    el.style.display = 'none';
                });
            } catch (e) { /* 个别站点选择器非法，忽略 */ }
        });
    }

    function hideByBadge() {
        var candidates = document.querySelectorAll('span, i, em, b, font, div');
        for (var i = 0; i < candidates.length; i++) {
            var el = candidates[i];
            if (el.children.length > 0) continue;                       // 只看叶子节点
            if ((el.textContent || '').trim() !== '广告') continue;      // 文字必须恰好是“广告”
            if (el.offsetWidth > 120 || el.offsetHeight > 40) continue;  // 角标都很小
            var box = el.closest(CONTAINER_SELECTOR);
            if (box) box.style.display = 'none';
        }
    }

    function clean() {
        hideBySelector();
        hideByBadge();
    }

    var timer = null;
    var observer = new MutationObserver(function () {
        if (timer) return;
        timer = setTimeout(function () {
            timer = null;
            clean();
        }, 300);
    });

    clean();
    // 搜索结果大多是异步加载的，盯紧后续 DOM 变化
    observer.observe(document.documentElement, { childList: true, subtree: true });
})();
