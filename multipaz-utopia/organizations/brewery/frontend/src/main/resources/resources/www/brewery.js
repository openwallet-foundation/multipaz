// ---------------------------------------------------------------------------
// Product catalog — source of truth for names, prices, and display data
// ---------------------------------------------------------------------------
const PRODUCTS = [
    {
        id: 1,
        name: "Old Oak Bourbon No. 12",
        price: "84.00",
        label: "LIMITED RELEASE ARCHIVE",
        description: "A masterfully crafted straight bourbon, aged twelve years in deeply charred American white oak. Rich caramel and vanilla on the nose with a long, warming finish.",
        flavors: ["Caramel", "Vanilla", "Toasted Oak", "Dried Cherry"],
        image: "images/bourbon.png",
        abv: "45% Vol.",
        cask: "American White Oak",
        maturation: "12 Years",
        texture: "Rich, Full-Bodied",
        origin: "Kentucky, USA",
        batch: "Archive #012-A",
        availability: "28 Bottles Remaining",
        philosophy: "The Keeper's Philosophy",
        philosophyBody: "Every barrel of Old Oak Bourbon No. 12 tells the story of patience. Selected from the highest rickhouse floors where temperature swings are most dramatic, this expression embodies the relentless push and pull between spirit and wood."
    },
    {
        id: 2,
        name: "Highland Botanical Gin",
        price: "52.00",
        label: "SINGLE DISTILLERY RELEASE",
        description: "A wild-flora distillation capturing the windswept botanicals of the Scottish Highlands. Juniper-forward with bright citrus and heather on the finish.",
        flavors: ["Juniper", "Heather", "Citrus Peel", "Wild Chamomile"],
        image: "images/gin.png",
        abv: "43% Vol.",
        cask: "Neutral Spirit Base",
        maturation: "No Age Statement",
        texture: "Crisp, Aromatic",
        origin: "Scottish Highlands",
        batch: "Archive #027-G",
        availability: "54 Bottles Remaining",
        philosophy: "The Keeper's Philosophy",
        philosophyBody: "Foraged botanicals gathered at the peak of summer bloom are cold-macerated for 48 hours before distillation. The result is a gin that changes subtly with each vintage, an honest reflection of the land that year."
    },
    {
        id: 3,
        name: "Winter Wheat Vodka",
        price: "48.00",
        label: "SMALL BATCH RELEASE",
        description: "Triple-filtered through activated charcoal, this small-batch vodka is built on a foundation of heritage winter wheat. Clean, slightly sweet, and silky smooth.",
        flavors: ["Soft Grain", "Light Cream", "White Pepper", "Mineral"],
        image: "images/vodka.png",
        abv: "40% Vol.",
        cask: "Neutral",
        maturation: "Unaged",
        texture: "Silky, Clean",
        origin: "Midwest Plains, USA",
        batch: "Archive #003-V",
        availability: "72 Bottles Remaining",
        philosophy: "The Keeper's Philosophy",
        philosophyBody: "Triple filtration is not a shortcut — it is a discipline. Each pass removes only what does not belong, leaving behind a spirit of remarkable purity that reflects the quiet dedication of the grain farmers who grow our wheat."
    },
    {
        id: 4,
        name: "Dark Port Spiced Rum",
        price: "65.00",
        label: "CASK STRENGTH RELEASE",
        description: "Aged eight years in ex-port wine casks, this cask-strength rum is layered with dark fruit, molasses, and a complex bouquet of warming spices.",
        flavors: ["Dark Molasses", "Port Wine", "Clove", "Ripe Plum"],
        image: "images/rum.png",
        abv: "58.3% Vol.",
        cask: "Ex-Port Wine Casks",
        maturation: "8 Years",
        texture: "Dense, Warming",
        origin: "Caribbean",
        batch: "Archive #041-R",
        availability: "19 Bottles Remaining",
        philosophy: "The Keeper's Philosophy",
        philosophyBody: "Port casks impart a density and complexity rarely achieved in rum. This expression was rested an additional six months after cask finishing, allowing the fruit and spice notes to fully integrate before release."
    },
    {
        id: 5,
        name: "Heritage Batch Rye",
        price: "72.00",
        label: "HERITAGE SERIES",
        description: "A boldly spiced rye whiskey built on a high-rye mash bill, delivering the classic peppery backbone alongside unexpected notes of honey and dried herbs.",
        flavors: ["Spicy Pepper", "Honey", "Dried Herbs", "Butterscotch"],
        image: "images/rye.png",
        abv: "46% Vol.",
        cask: "New American Oak",
        maturation: "7 Years",
        texture: "Bold, Spicy",
        origin: "Pennsylvania, USA",
        batch: "Archive #008-H",
        availability: "33 Bottles Remaining",
        philosophy: "The Keeper's Philosophy",
        philosophyBody: "Rye whiskey demands respect for its character. This heritage batch is built on a 95% rye mash bill — uncompromising and unapologetic — aged in the same style as the great pre-Prohibition distillers of the region."
    },
    {
        id: 6,
        name: "Islay Mist Single Malt",
        price: "110.00",
        label: "ISLAND COLLECTION",
        description: "A masterfully curated single malt, aged for twenty-four winters in charred oak barrels. This expression captures the essence of coastal storms and lingering hearth-fire.",
        flavors: ["Smoky Ember", "Wild Honey", "Toasted Peppercorn", "Sea Salt"],
        image: "images/scotch.png",
        abv: "48.2% Vol.",
        cask: "Ex-Bourbon & Sherry Casks",
        maturation: "24 Years Private Cellar",
        texture: "Silky, Full-Bodied",
        origin: "Isle of Jura, Scotland",
        batch: "Archive #082-C",
        availability: "42 Bottles Remaining",
        philosophy: "The Keeper's Philosophy",
        philosophyBody: "Every bottle in the Distilled Archive represents a specific moment in time. The Islay Mist was distilled during a particularly harsh lunar cycle in the autumn of 2000. The interaction between the plummeting mercury and the density of the coastal air created a unique profile that cannot be replicated."
    }
];

// ---------------------------------------------------------------------------
// Page initialisation
// ---------------------------------------------------------------------------
window.addEventListener("DOMContentLoaded", function () {
    const params = new URLSearchParams(location.search);
    const id = parseInt(params.get("id")) || 1;
    const product = PRODUCTS.find(function (p) { return p.id === id; }) || PRODUCTS[0];
    window._currentProduct = product;

    document.getElementById("product-image").src = product.image;
    document.getElementById("product-image").alt = product.name;
    document.getElementById("product-label").textContent = product.label;
    document.getElementById("product-name").textContent = product.name;
    document.getElementById("product-description").textContent = product.description;
    document.getElementById("product-price").textContent = "$" + product.price;
    document.getElementById("product-availability").textContent = product.availability;
    document.getElementById("meta-abv").textContent = product.abv;
    document.getElementById("meta-cask").textContent = product.cask;
    document.getElementById("meta-maturation").textContent = product.maturation;
    document.getElementById("meta-texture").textContent = product.texture;
    document.getElementById("meta-origin").textContent = product.origin;
    document.getElementById("meta-batch").textContent = product.batch;
    document.getElementById("philosophy-heading").textContent = product.philosophy;
    document.getElementById("philosophy-body").textContent = product.philosophyBody;
    document.title = product.name + " — Utopia Brewery";

    const flavorsEl = document.getElementById("product-flavors");
    product.flavors.forEach(function (flavor) {
        const tag = document.createElement("span");
        tag.className = "flavor-tag";
        tag.textContent = flavor;
        flavorsEl.appendChild(tag);
    });
});

// ---------------------------------------------------------------------------
// Buy button handler
// ---------------------------------------------------------------------------
async function onBuyClick() {
    const product = window._currentProduct;
    if (!product) return;

    showLoading(true);
    document.getElementById("buy-btn").disabled = true;

    try {
        // Step 1: Get DCQL + transaction_data from backend
        const checkoutResp = await fetch("checkout", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ productName: product.name, price: product.price })
        });
        if (!checkoutResp.ok) {
            throw new Error("Checkout request failed (" + checkoutResp.status + ")");
        }
        const checkoutData = await checkoutResp.json();

        // Step 2: Present credentials via DC API (multipazVerifyCredentials is from verify_credentials.js)
        const result = await multipazVerifyCredentials(checkoutData);

        showLoading(false);

        // Step 3: Display result — BreweryVerifierAssistant injects approved/holderName/issuerName/error
        if (result && result.approved) {
            showResult(true,
                "<h2>Purchase Approved</h2>" +
                "<p>Welcome, <strong>" + escapeHtml(result.holderName) + "</strong>.</p>" +
                "<p>Your order for <strong>" + escapeHtml(product.name) + "</strong> " +
                "($" + escapeHtml(product.price) + ") has been recorded.</p>" +
                "<p>Payment instrument via <em>" + escapeHtml(result.issuerName) + "</em>.</p>"
            );
        } else {
            const msg = (result && result.error) ? result.error : "Verification could not be completed.";
            showResult(false, "<h2>Purchase Declined</h2><p>" + escapeHtml(msg) + "</p>");
        }
    } catch (err) {
        showLoading(false);
        showResult(false,
            "<h2>Presentation Cancelled</h2><p>" + escapeHtml(err.message || String(err)) + "</p>"
        );
    } finally {
        document.getElementById("buy-btn").disabled = false;
    }
}

// ---------------------------------------------------------------------------
// Overlay helpers
// ---------------------------------------------------------------------------
function showLoading(visible) {
    document.getElementById("loading-overlay").classList.toggle("hidden", !visible);
}

function showResult(approved, html) {
    const box = document.getElementById("result-box");
    box.className = "result-box " + (approved ? "result-approved" : "result-declined");
    document.getElementById("result-content").innerHTML = html;
    document.getElementById("result-overlay").classList.remove("hidden");
}

function closeOverlay() {
    document.getElementById("result-overlay").classList.add("hidden");
}

function escapeHtml(str) {
    return String(str)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;");
}
