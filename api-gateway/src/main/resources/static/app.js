// OrderFlow Enterprise Distributed Commerce — Client Application

let currentUser = null;
let currentToken = null;
let currentProducts = [];
let currentCart = { items: [], totalItems: 0, totalAmount: 0 };
let activeProduct = null;
let selectedVariant = null;
let activeOrderForPayment = null;
let selectedPaymentMethod = 'CREDIT_CARD';
let userAddresses = [];
let selectedAddressId = null;
let telemetryInterval = null;
let notifInterval = null;
const productQuantities = {}; // Maps productId -> quantity for independent product counters

// Initialization
document.addEventListener('DOMContentLoaded', () => {
  initAuth();
  window.addEventListener('hashchange', router);
  router(); // Handle initial route
});

// Router
function router() {
  const hash = window.location.hash || '#/';
  hideAllViews();

  if (hash === '#/' || hash === '#/products') {
    showView('view-catalog');
    setActiveNavLink('navCatalog');
    loadProducts();
  } else if (hash.startsWith('#/products/')) {
    const id = hash.split('/')[2];
    showView('view-product-detail');
    loadProductDetail(id);
  } else if (hash === '#/cart') {
    showView('view-cart');
    loadCartView();
  } else if (hash.startsWith('#/checkout/') && hash.endsWith('/pay')) {
    const parts = hash.split('/');
    const orderId = parts[2];
    showView('view-checkout-pay');
    loadCheckoutPay(orderId);
  } else if (hash === '#/profile') {
    if (!requireAuth()) return;
    showView('view-profile');
    loadProfileView();
  } else if (hash === '#/profile/orders') {
    if (!requireAuth()) return;
    showView('view-orders');
    setActiveNavLink('navOrders');
    loadOrders();
  } else if (hash.startsWith('#/seller/')) {
    if (!requireRole(['ROLE_SELLER', 'ROLE_ADMIN'])) return;
    showView('view-seller-dashboard');
    setActiveNavLink('navSeller');
    const subtab = hash.includes('/orders') ? 'orders' : 'products';
    switchSellerTab(subtab);
    loadSellerDashboard();
  } else if (hash.startsWith('#/auth/')) {
    showView('view-auth');
    const mode = hash.split('/')[2] || 'login';
    switchAuthMode(mode);
  } else if (hash === '#/admin/telemetry') {
    if (!requireRole(['ROLE_ADMIN'])) return;
    showView('view-telemetry');
    setActiveNavLink('navTelemetry');
    startTelemetryPolling();
  } else {
    showView('view-catalog');
    loadProducts();
  }
}

function navigate(path) {
  window.location.hash = '#' + (path.startsWith('/') ? path : '/' + path);
}

function hideAllViews() {
  document.querySelectorAll('.page-view').forEach(v => v.classList.remove('active'));
  document.querySelectorAll('.nav-link').forEach(l => l.classList.remove('active'));
}

function showView(viewId) {
  const el = document.getElementById(viewId);
  if (el) el.classList.add('active');
  window.scrollTo(0, 0);
}

function setActiveNavLink(linkId) {
  const link = document.getElementById(linkId);
  if (link) link.classList.add('active');
}

// Authentication
function initAuth() {
  const savedToken = localStorage.getItem('orderflow_token');
  const savedUser = localStorage.getItem('orderflow_user');

  if (savedToken && savedUser) {
    try {
      currentToken = savedToken;
      currentUser = JSON.parse(savedUser);
      updateAuthUI();
      startNotificationPolling();
    } catch (e) {
      logout();
    }
  } else {
    updateAuthUI();
  }
}

function updateAuthUI() {
  const userChip = document.getElementById('userChip');
  const btnLoginNav = document.getElementById('btnLoginNav');
  const btnRegisterNav = document.getElementById('btnRegisterNav');
  const btnLogoutNav = document.getElementById('btnLogoutNav');
  const navSeller = document.getElementById('navSeller');
  const navTelemetry = document.getElementById('navTelemetry');
  const notifWrapper = document.getElementById('notifWrapper');

  if (currentUser && currentToken) {
    if (userChip) {
      userChip.style.display = 'flex';
      document.getElementById('userAvatar').innerText = (currentUser.fullName || currentUser.email || 'U').charAt(0).toUpperCase();
      document.getElementById('userName').innerText = currentUser.fullName || currentUser.email;
      document.getElementById('userRole').innerText = (currentUser.role || 'ROLE_CUSTOMER').replace('ROLE_', '');
    }
    if (btnLoginNav) btnLoginNav.style.display = 'none';
    if (btnRegisterNav) btnRegisterNav.style.display = 'none';
    if (btnLogoutNav) btnLogoutNav.style.display = 'flex';
    if (notifWrapper) notifWrapper.style.display = 'block';

    const isSellerOrAdmin = currentUser.role === 'ROLE_SELLER' || currentUser.role === 'ROLE_ADMIN';
    if (navSeller) navSeller.style.display = isSellerOrAdmin ? 'block' : 'none';
    if (navTelemetry) navTelemetry.style.display = currentUser.role === 'ROLE_ADMIN' ? 'block' : 'none';
  } else {
    if (userChip) userChip.style.display = 'none';
    if (btnLoginNav) btnLoginNav.style.display = 'block';
    if (btnRegisterNav) btnRegisterNav.style.display = 'block';
    if (btnLogoutNav) btnLogoutNav.style.display = 'none';
    if (navSeller) navSeller.style.display = 'none';
    if (navTelemetry) navTelemetry.style.display = 'none';
    if (notifWrapper) notifWrapper.style.display = 'none';
    stopNotificationPolling();
    stopTelemetryPolling();
  }
  loadCartBadge();
}

function setSession(data) {
  currentToken = data.token;
  currentUser = {
    id: data.userId,
    email: data.email,
    fullName: data.fullName,
    role: data.role
  };
  localStorage.setItem('orderflow_token', currentToken);
  localStorage.setItem('orderflow_user', JSON.stringify(currentUser));
  updateAuthUI();
  startNotificationPolling();
  showToast(`Welcome back, ${currentUser.fullName || currentUser.email}!`, 'success');
}

function logout() {
  localStorage.removeItem('orderflow_token');
  localStorage.removeItem('orderflow_user');
  currentUser = null;
  currentToken = null;
  updateAuthUI();
  showToast('You have been signed out.', 'info');
  navigate('/');
}

function requireAuth() {
  if (!currentToken) {
    showToast('Please sign in to access this page.', 'info');
    navigate('/auth/login');
    return false;
  }
  return true;
}

function requireRole(allowedRoles) {
  if (!requireAuth()) return false;
  if (!allowedRoles.includes(currentUser.role)) {
    showToast('Access denied: You do not have permission for this section.', 'error');
    navigate('/');
    return false;
  }
  return true;
}

// Authentication View Handlers
function switchAuthMode(mode) {
  const tabs = {
    login: document.getElementById('authTabLogin'),
    register: document.getElementById('authTabRegCust'),
    'register-seller': document.getElementById('authTabRegSeller')
  };
  const forms = {
    login: document.getElementById('loginForm'),
    register: document.getElementById('regCustForm'),
    'register-seller': document.getElementById('regSellerForm')
  };

  Object.keys(tabs).forEach(k => {
    if (tabs[k]) tabs[k].classList.toggle('active', k === mode);
    if (forms[k]) forms[k].style.display = k === mode ? 'block' : 'none';
  });
}

function quickFillLogin(email, password) {
  document.getElementById('loginEmail').value = email;
  document.getElementById('loginPassword').value = password;
  const form = document.getElementById('loginForm');
  if (form) {
    if (typeof form.requestSubmit === 'function') {
      form.requestSubmit();
    } else {
      form.dispatchEvent(new Event('submit', { cancelable: true }));
    }
  }
}

async function handleAuthLogin(e) {
  e.preventDefault();
  const email = document.getElementById('loginEmail').value.trim();
  const password = document.getElementById('loginPassword').value;
  const btn = document.getElementById('btnLoginSubmit');
  btn.disabled = true;
  btn.innerText = 'Signing In...';

  try {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password })
    });
    const data = await res.json();
    if (!res.ok) {
      showToast(data.message || 'Invalid credentials.', 'error');
      return;
    }
    setSession(data);
    if (data.role === 'ROLE_SELLER') {
      navigate('/seller/products');
    } else if (data.role === 'ROLE_ADMIN') {
      navigate('/admin/telemetry');
    } else {
      navigate('/');
    }
  } catch (err) {
    showToast('Login failed: ' + err.message, 'error');
  } finally {
    btn.disabled = false;
    btn.innerText = 'Sign In';
  }
}

async function handleAuthRegister(e, type) {
  e.preventDefault();
  const isSeller = type === 'SELLER';
  const fullName = isSeller ? document.getElementById('regSellerName').value.trim() : document.getElementById('regCustName').value.trim();
  const email = isSeller ? document.getElementById('regSellerEmail').value.trim() : document.getElementById('regCustEmail').value.trim();
  const password = isSeller ? document.getElementById('regSellerPassword').value : document.getElementById('regCustPassword').value;
  const businessName = isSeller ? document.getElementById('regSellerBusinessName').value.trim() : null;

  const endpoint = isSeller ? '/api/auth/register-seller' : '/api/auth/register';

  try {
    const res = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password, fullName, businessName })
    });
    const data = await res.json();
    if (!res.ok) {
      showToast(data.message || 'Registration failed.', 'error');
      return;
    }

    setSession(data);

    if (isSeller) {
      if (businessName) {
        // Update business name on seller profile
        await fetch('/api/seller/profile', {
          method: 'PUT',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${currentToken}`
          },
          body: JSON.stringify({ businessName, contactEmail: email })
        });
      }
      navigate('/seller/products');
    } else {
      navigate('/');
    }
  } catch (err) {
    showToast('Registration failed: ' + err.message, 'error');
  }
}

// Product Catalog View
async function loadProducts() {
  try {
    const res = await fetch('/api/products?page=0&size=50');
    if (!res.ok) return;
    const data = await res.json();
    currentProducts = data.content || [];
    renderCatalogGrid(currentProducts);
  } catch (err) {
    console.error('Failed to load products:', err);
  }
}

function renderCatalogGrid(products) {
  const container = document.getElementById('productsContainer');
  if (!container) return;

  if (products.length === 0) {
    container.innerHTML = '<div class="empty-state">No products found matching the criteria.</div>';
    return;
  }

  container.innerHTML = products.map(p => {
    const defaultImg = 'https://images.unsplash.com/photo-1587829741301-dc798b83add3?w=500&q=80';
    const imgUrl = p.imageUrl || defaultImg;
    const variantCount = p.variants ? p.variants.length : 0;
    const stock = p.availableStock || 0;
    const stockClass = stock > 10 ? 'in-stock' : (stock > 0 ? 'low-stock' : 'out-of-stock');
    const stockText = stock > 0 ? `${stock} in stock` : 'Out of stock';

    const itemQty = productQuantities[p.id] || 1;

    return `
      <div class="product-card" onclick="navigate('/products/${p.id}')">
        <div class="product-thumb">
          <img src="${imgUrl}" alt="${p.name}">
          <span class="thumb-tag">${p.category || 'Hardware'}</span>
        </div>
        <div class="product-body">
          <div class="product-title">${p.name}</div>
          <div class="product-seller">SKU: ${p.sku} ${variantCount > 1 ? `· ${variantCount} Variants` : ''}</div>
          <div class="rating-preview">
            <span>★★★★★</span>
            <span class="rating-count">4.8 (Verified)</span>
          </div>
          <div class="product-footer">
            <div class="product-price">₹${Number(p.price).toFixed(2)}</div>
            <span class="stock-indicator ${stockClass}">${stockText}</span>
          </div>
          <div class="card-action-row" onclick="event.stopPropagation()">
            <div class="stepper card-stepper">
              <button class="step-btn" onclick="adjustCardQuantity(${p.id}, -1)">-</button>
              <input type="number" id="cardQty-${p.id}" value="${itemQty}" min="1" max="99" readonly>
              <button class="step-btn" onclick="adjustCardQuantity(${p.id}, 1)">+</button>
            </div>
            <button class="btn-primary card-add-btn" onclick="addCardToCart(${p.id})" ${stock <= 0 ? 'disabled' : ''}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="8" cy="21" r="1"/>
                <circle cx="19" cy="21" r="1"/>
                <path d="M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12"/>
              </svg>
              <span>Add</span>
            </button>
          </div>
        </div>
      </div>
    `;
  }).join('');
}

function adjustCardQuantity(productId, delta) {
  let val = (productQuantities[productId] || 1) + delta;
  if (val < 1) val = 1;
  if (val > 99) val = 99;
  productQuantities[productId] = val;
  const input = document.getElementById(`cardQty-${productId}`);
  if (input) input.value = val;

  // Sync detail view counter if this is currently the active viewed product
  if (activeProduct && activeProduct.id === productId) {
    const detailInput = document.getElementById('detailQuantity');
    if (detailInput) detailInput.value = val;
  }
}

async function addCardToCart(productId) {
  const qty = productQuantities[productId] || 1;
  const prod = currentProducts.find(p => p.id === productId);
  const defVar = (prod && prod.variants && prod.variants.length > 0)
    ? (prod.variants.find(v => v.isDefault) || prod.variants[0])
    : null;
  const variantId = defVar ? defVar.id : null;

  try {
    const headers = { 'Content-Type': 'application/json' };
    if (currentToken) headers['Authorization'] = `Bearer ${currentToken}`;

    const res = await fetch('/api/cart/items', {
      method: 'POST',
      headers,
      body: JSON.stringify({
        productId,
        variantId,
        quantity: qty
      })
    });

    if (res.ok) {
      currentCart = await res.json();
      updateCartBadge(currentCart.totalItems);
      showToast(`Added ${qty} item(s) to cart!`, 'success');
    } else {
      const err = await res.json();
      showToast(err.message || 'Failed to add item to cart.', 'error');
    }
  } catch (err) {
    showToast('Network error while adding to cart.', 'error');
  }
}

function selectCategory(cat) {
  document.querySelectorAll('#categoryPills .pill').forEach(btn => {
    btn.classList.toggle('active', btn.innerText.toLowerCase().includes(cat.toLowerCase()) || (cat === 'ALL' && btn.innerText.includes('All')));
  });

  if (cat === 'ALL') {
    renderCatalogGrid(currentProducts);
  } else {
    const target = cat.toLowerCase();
    const filtered = currentProducts.filter(p => {
      const pCat = (p.category || '').toLowerCase();
      if (pCat === target) return true;
      if ((target === 'clothes' || target === 'clothing') && (pCat === 'clothes' || pCat === 'clothing')) return true;
      if ((target === 'jewellery' || target === 'jewelry') && (pCat === 'jewellery' || pCat === 'jewelry')) return true;
      return false;
    });
    renderCatalogGrid(filtered);
  }
}

function filterProducts() {
  const query = document.getElementById('catalogSearchInput').value.toLowerCase();
  const filtered = currentProducts.filter(p =>
    p.name.toLowerCase().includes(query) ||
    p.sku.toLowerCase().includes(query) ||
    (p.category || '').toLowerCase().includes(query)
  );
  renderCatalogGrid(filtered);
}

// Product Detail View
async function loadProductDetail(id) {
  try {
    const res = await fetch(`/api/products/${id}`);
    if (!res.ok) {
      showToast('Product not found.', 'error');
      navigate('/');
      return;
    }
    activeProduct = await res.json();
    renderProductDetailView(activeProduct);
    loadProductReviews(id);
  } catch (err) {
    showToast('Failed to load product details.', 'error');
  }
}

function renderProductDetailView(p) {
  document.getElementById('detailBreadcrumbName').innerText = p.name;
  document.getElementById('detailTitle').innerText = p.name;
  document.getElementById('detailDescription').innerText = p.description || 'Precision engineered hardware with rigorous component isolation.';
  document.getElementById('detailCategory').innerText = p.category || 'Hardware';
  document.getElementById('detailSeller').innerText = p.sellerId ? `Seller #${p.sellerId}` : 'Verified Partner';
  document.getElementById('detailImage').src = p.imageUrl || 'https://images.unsplash.com/photo-1587829741301-dc798b83add3?w=800&q=80';

  const variantSection = document.getElementById('detailVariantSection');
  const chipsContainer = document.getElementById('detailVariantChips');

  if (p.variants && p.variants.length > 0) {
    variantSection.style.display = 'block';
    selectedVariant = p.variants.find(v => v.isDefault) || p.variants[0];

    chipsContainer.innerHTML = p.variants.map(v => `
      <button class="variant-chip ${v.id === selectedVariant.id ? 'active' : ''}" onclick="selectDetailVariant(${v.id})">
        ${v.title} · ₹${Number(v.price).toFixed(2)} (${v.availableStock} in stock)
      </button>
    `).join('');
  } else {
    variantSection.style.display = 'none';
    selectedVariant = null;
  }

  // Set quantity counter specific to this individual product
  const currentQty = productQuantities[p.id] || 1;
  const qtyInput = document.getElementById('detailQuantity');
  if (qtyInput) {
    qtyInput.value = currentQty;
  }

  updateDetailPriceAndStock();
}

function selectDetailVariant(variantId) {
  if (!activeProduct || !activeProduct.variants) return;
  selectedVariant = activeProduct.variants.find(v => v.id === variantId);
  document.querySelectorAll('#detailVariantChips .variant-chip').forEach((btn, idx) => {
    btn.classList.toggle('active', activeProduct.variants[idx].id === variantId);
  });
  updateDetailPriceAndStock();
}

function updateDetailPriceAndStock() {
  if (!activeProduct) return;
  const price = selectedVariant ? selectedVariant.price : activeProduct.price;
  const stock = selectedVariant ? selectedVariant.availableStock : activeProduct.availableStock;

  document.getElementById('detailPrice').innerText = `₹${Number(price).toFixed(2)}`;
  const stockEl = document.getElementById('detailStock');
  stockEl.innerText = stock > 0 ? `In Stock (${stock} units)` : 'Out of Stock';
  stockEl.className = 'stock-indicator ' + (stock > 10 ? 'in-stock' : (stock > 0 ? 'low-stock' : 'out-of-stock'));
}

function adjustDetailQuantity(delta) {
  if (!activeProduct) return;
  const input = document.getElementById('detailQuantity');
  let val = parseInt(input.value || 1) + delta;
  if (val < 1) val = 1;
  if (val > 99) val = 99;
  input.value = val;
  productQuantities[activeProduct.id] = val;

  const cardInput = document.getElementById(`cardQty-${activeProduct.id}`);
  if (cardInput) {
    cardInput.value = val;
  }
}

async function addDetailToCart() {
  if (!activeProduct) return;
  const qty = parseInt(document.getElementById('detailQuantity').value);
  const variantId = selectedVariant ? selectedVariant.id : null;

  try {
    const headers = { 'Content-Type': 'application/json' };
    if (currentToken) headers['Authorization'] = `Bearer ${currentToken}`;

    const res = await fetch('/api/cart/items', {
      method: 'POST',
      headers,
      body: JSON.stringify({
        productId: activeProduct.id,
        variantId: variantId,
        quantity: qty
      })
    });

    if (!res.ok) {
      const err = await res.json();
      showToast(err.message || 'Failed to add item to cart.', 'error');
      return;
    }

    const cart = await res.json();
    currentCart = cart;
    updateCartBadge(cart.totalItems);
    showToast(`Added ${qty}x ${activeProduct.name} to cart.`, 'success');
  } catch (err) {
    showToast('Failed to update cart: ' + err.message, 'error');
  }
}

async function buyNowFromDetail() {
  await addDetailToCart();
  navigate('/cart');
}

// Product Reviews
async function loadProductReviews(productId) {
  try {
    const res = await fetch(`/api/products/${productId}/reviews`);
    if (!res.ok) return;
    const data = await res.json();

    const score = Number(data.averageRating || 0).toFixed(1);
    const count = data.totalReviews || 0;

    document.getElementById('reviewAvgScore').innerText = score;
    document.getElementById('reviewCountLabel').innerText = `Based on ${count} customer review${count === 1 ? '' : 's'}`;
    document.getElementById('detailRatingText').innerText = `${score} (${count} review${count === 1 ? '' : 's'})`;

    const listContainer = document.getElementById('reviewsList');
    if (!data.reviews || data.reviews.length === 0) {
      listContainer.innerHTML = '<div class="empty-state">No reviews yet. Be the first to review this product!</div>';
      return;
    }

    listContainer.innerHTML = data.reviews.map(r => `
      <div class="review-card">
        <div class="review-top">
          <span class="reviewer-name">
            ${r.userFullName || 'Customer'}
            ${r.verifiedPurchase ? '<span class="verified-tag">✓ Verified Purchase</span>' : ''}
          </span>
          <span class="stars">${'★'.repeat(r.rating)}${'☆'.repeat(5 - r.rating)}</span>
        </div>
        <p class="review-comment">${r.comment}</p>
        <span class="notif-time">${new Date(r.createdAt).toLocaleDateString()}</span>
      </div>
    `).join('');
  } catch (err) {
    console.error('Failed to load reviews:', err);
  }
}

function toggleReviewForm() {
  if (!requireAuth()) return;
  const card = document.getElementById('addReviewCard');
  card.style.display = card.style.display === 'none' ? 'block' : 'none';
}

function setFormRating(val) {
  document.getElementById('formRatingVal').value = val;
  document.querySelectorAll('.star-pick').forEach(s => {
    const sVal = parseInt(s.getAttribute('data-val'));
    s.classList.toggle('active', sVal <= val);
  });
}

async function handleReviewSubmit(e) {
  e.preventDefault();
  if (!requireAuth() || !activeProduct) return;

  const rating = parseInt(document.getElementById('formRatingVal').value);
  const comment = document.getElementById('reviewComment').value.trim();

  try {
    const res = await fetch(`/api/products/${activeProduct.id}/reviews`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${currentToken}`
      },
      body: JSON.stringify({ rating, comment })
    });

    if (!res.ok) {
      const err = await res.json();
      showToast(err.message || 'Failed to submit review.', 'error');
      return;
    }

    showToast('Review submitted successfully!', 'success');
    document.getElementById('reviewComment').value = '';
    toggleReviewForm();
    loadProductReviews(activeProduct.id);
  } catch (err) {
    showToast('Error submitting review: ' + err.message, 'error');
  }
}

function scrollToReviews() {
  document.getElementById('reviewsContainer')?.scrollIntoView({ behavior: 'smooth' });
}

// Cart View & Checkout
async function loadCartView() {
  try {
    const headers = {};
    if (currentToken) headers['Authorization'] = `Bearer ${currentToken}`;

    const res = await fetch('/api/cart', { headers });
    if (!res.ok) return;
    currentCart = await res.json();
    renderCartPage(currentCart);

    if (currentToken) {
      loadUserAddressesForCart();
    }
  } catch (err) {
    console.error('Failed to load cart:', err);
  }
}

function renderCartPage(cart) {
  const layout = document.getElementById('cartLayout');
  const empty = document.getElementById('emptyCartState');
  const list = document.getElementById('cartItemsList');

  if (!cart.items || cart.items.length === 0) {
    layout.style.display = 'none';
    empty.style.display = 'block';
    updateCartBadge(0);
    return;
  }

  layout.style.display = 'grid';
  empty.style.display = 'none';
  updateCartBadge(cart.totalItems);

  document.getElementById('cartSubtotal').innerText = `₹${Number(cart.totalAmount).toFixed(2)}`;
  document.getElementById('cartTotal').innerText = `₹${Number(cart.totalAmount).toFixed(2)}`;

  list.innerHTML = cart.items.map(item => `
    <div class="cart-item-row">
      <img class="cart-item-thumb" src="${item.imageUrl || 'https://images.unsplash.com/photo-1587829741301-dc798b83add3?w=200&q=80'}" alt="${item.productName}">
      <div class="cart-item-info">
        <div class="cart-item-title">${item.productName}</div>
        <div class="cart-item-variant">${item.variantTitle ? `Variant: ${item.variantTitle}` : `SKU: ${item.sku}`}</div>
        <div class="cart-item-price">₹${Number(item.price).toFixed(2)} each</div>
      </div>
      <div class="stepper">
        <button class="step-btn" onclick="updateCartItemQty(${item.id}, ${item.quantity - 1})">-</button>
        <input type="number" value="${item.quantity}" readonly>
        <button class="step-btn" onclick="updateCartItemQty(${item.id}, ${item.quantity + 1})">+</button>
      </div>
      <div style="font-weight:700;min-width:70px;text-align:right;">
        ₹${Number(item.subtotal).toFixed(2)}
      </div>
      <button class="btn-ghost" onclick="removeCartItem(${item.id})" title="Remove">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="3 6 5 6 21 6"></polyline>
          <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
        </svg>
      </button>
    </div>
  `).join('');
}

async function updateCartItemQty(itemId, newQty) {
  if (newQty <= 0) {
    removeCartItem(itemId);
    return;
  }

  const headers = { 'Content-Type': 'application/json' };
  if (currentToken) headers['Authorization'] = `Bearer ${currentToken}`;

  try {
    const res = await fetch(`/api/cart/items/${itemId}?quantity=${newQty}`, {
      method: 'PUT',
      headers,
      body: JSON.stringify({ quantity: newQty })
    });
    if (res.ok) {
      currentCart = await res.json();
      updateCartBadge(currentCart.totalItems);
      renderCartPage(currentCart);
    }
  } catch (err) {
    showToast('Failed to update quantity.', 'error');
  }
}

async function removeCartItem(itemId) {
  const headers = {};
  if (currentToken) headers['Authorization'] = `Bearer ${currentToken}`;

  try {
    const res = await fetch(`/api/cart/items/${itemId}`, { method: 'DELETE', headers });
    if (res.ok) {
      currentCart = await res.json();
      renderCartPage(currentCart);
      showToast('Item removed from cart.', 'info');
    }
  } catch (err) {
    showToast('Failed to remove item.', 'error');
  }
}

async function loadCartBadge() {
  try {
    const headers = {};
    if (currentToken) headers['Authorization'] = `Bearer ${currentToken}`;
    const res = await fetch('/api/cart', { headers });
    if (res.ok) {
      const cart = await res.json();
      updateCartBadge(cart.totalItems || 0);
    }
  } catch (e) {}
}

function updateCartBadge(count) {
  const badge = document.getElementById('cartBadge');
  if (badge) badge.innerText = count || 0;
}

async function loadUserAddressesForCart() {
  try {
    const res = await fetch('/api/users/me/addresses', {
      headers: { 'Authorization': `Bearer ${currentToken}` }
    });
    if (!res.ok) return;
    userAddresses = await res.json();

    const selector = document.getElementById('cartAddressSelector');
    if (userAddresses.length === 0) {
      selector.innerHTML = '<div class="empty-notif">No saved addresses. Add one below.</div>';
      selectedAddressId = null;
      return;
    }

    const defaultAddr = userAddresses.find(a => a.isDefault) || userAddresses[0];
    selectedAddressId = defaultAddr ? defaultAddr.id : null;

    selector.innerHTML = userAddresses.map(a => `
      <div class="address-option ${a.id === selectedAddressId ? 'selected' : ''}" onclick="selectCartAddress(${a.id})">
        <strong>${a.recipientName}</strong> ${a.isDefault ? '<span class="verified-tag">Default</span>' : ''}
        <div style="color:var(--text-muted);font-size:0.8rem;">${a.streetAddress}, ${a.city}, ${a.state} ${a.postalCode}</div>
      </div>
    `).join('');
  } catch (e) {}
}

function selectCartAddress(id) {
  selectedAddressId = id;
  document.querySelectorAll('#cartAddressSelector .address-option').forEach((opt, idx) => {
    opt.classList.toggle('selected', userAddresses[idx].id === id);
  });
}

async function proceedToCheckout() {
  if (!requireAuth()) return;
  if (!currentCart || !currentCart.items || currentCart.items.length === 0) {
    showToast('Your cart is empty.', 'error');
    return;
  }

  try {
    const res = await fetch('/api/cart/checkout', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${currentToken}`
      },
      body: JSON.stringify({ addressId: selectedAddressId })
    });

    if (!res.ok) {
      const err = await res.json();
      showToast(err.message || 'Checkout failed.', 'error');
      return;
    }

    const order = await res.json();
    showToast(`Order #${order.orderNumber} created in AWAITING_PAYMENT.`, 'success');
    updateCartBadge(0);
    navigate(`/checkout/${order.id}/pay`);
  } catch (err) {
    showToast('Checkout failed: ' + err.message, 'error');
  }
}

// Dedicated Checkout & Payment Page
async function loadCheckoutPay(orderId) {
  if (!requireAuth()) return;

  try {
    const res = await fetch(`/api/orders/${orderId}`, {
      headers: { 'Authorization': `Bearer ${currentToken}` }
    });

    if (!res.ok) {
      showToast('Order not found.', 'error');
      navigate('/profile/orders');
      return;
    }

    activeOrderForPayment = await res.json();
    renderCheckoutPayView(activeOrderForPayment);
  } catch (err) {
    showToast('Failed to load order for payment: ' + err.message, 'error');
  }
}

function renderCheckoutPayView(order) {
  document.getElementById('payOrderRef').innerText = `Order #${order.orderNumber}`;
  document.getElementById('payOrderStatus').innerText = order.status;
  document.getElementById('payOrderDate').innerText = new Date(order.createdAt).toLocaleDateString();
  document.getElementById('payOrderTotal').innerText = `₹${Number(order.totalAmount).toFixed(2)}`;
  document.getElementById('payAmountLabel').innerText = `(₹${Number(order.totalAmount).toFixed(2)})`;

  const addrEl = document.getElementById('payShippingAddress');
  if (order.shippingAddress) {
    const a = order.shippingAddress;
    addrEl.innerHTML = `
      <strong>${a.recipientName || currentUser.fullName}</strong>
      <div>${a.streetAddress || ''} ${a.apartmentSuite || ''}</div>
      <div>${a.city || ''}, ${a.state || ''} ${a.postalCode || ''}</div>
      <div style="font-size:0.75rem;color:var(--text-dim);margin-top:0.25rem;">Phone: ${a.phoneNumber || ''}</div>
    `;
  } else {
    addrEl.innerText = 'Standard Shipping Address';
  }

  selectPaymentMethod('CREDIT_CARD');
}

function selectPaymentMethod(method) {
  selectedPaymentMethod = method;
  document.querySelectorAll('.pm-tab').forEach(tab => {
    tab.classList.toggle('active', tab.getAttribute('data-method') === method);
  });

  const panel = document.getElementById('pmFormPanel');
  const simFail = document.getElementById('simFailBox');

  simFail.style.display = (method === 'CASH_ON_DELIVERY') ? 'none' : 'block';

  if (method === 'CREDIT_CARD') {
    panel.innerHTML = `
      <div class="form-group">
        <label>Card Number</label>
        <input type="text" value="4532 •••• •••• 8892" readonly>
      </div>
      <div class="form-row">
        <div class="form-group">
          <label>Expiry Date</label>
          <input type="text" value="12/28" readonly>
        </div>
        <div class="form-group">
          <label>CVV / CVC</label>
          <input type="password" value="888" readonly>
        </div>
      </div>
    `;
  } else if (method === 'UPI') {
    panel.innerHTML = `
      <div class="form-group">
        <label>Virtual Payment Address (VPA / UPI ID)</label>
        <input type="text" value="customer@okhdfcbank" readonly>
      </div>
      <p style="font-size:0.8rem;color:var(--text-dim);">Google Pay, PhonePe, and Paytm requests will be dispatched automatically.</p>
    `;
  } else if (method === 'NET_BANKING') {
    panel.innerHTML = `
      <div class="form-group">
        <label>Selected Financial Institution</label>
        <select>
          <option>HDFC Bank (NetBanking)</option>
          <option>ICICI Bank</option>
          <option>State Bank of India</option>
          <option>Axis Bank</option>
        </select>
      </div>
    `;
  } else if (method === 'CASH_ON_DELIVERY') {
    panel.innerHTML = `
      <div style="padding:1rem;background:rgba(16,185,129,0.08);border:1px solid rgba(16,185,129,0.25);border-radius:var(--radius-sm);font-size:0.85rem;color:var(--success);">
        <strong>Doorstep Cash Collection Active:</strong> Pay cash upon delivery. The courier agent will collect payment and automatically settle this transaction on completion.
      </div>
    `;
  }
}

function toggleGatewayMode() {
  const chk = document.getElementById('chkUseRazorpay');
  if (chk.checked) {
    showToast('Razorpay Gateway adapter mode activated with cryptographic HMAC-SHA256 signature verification.', 'info');
  }
}

async function executePayment() {
  if (!activeOrderForPayment || !requireAuth()) return;

  const btn = document.getElementById('btnSubmitPayment');
  btn.disabled = true;
  btn.innerText = 'Processing Transaction...';

  const useRazorpay = document.getElementById('chkUseRazorpay').checked && selectedPaymentMethod !== 'CASH_ON_DELIVERY';
  const simulateFailure = document.getElementById('chkSimulateFail').checked;

  try {
    if (useRazorpay) {
      // Step 1: Create Razorpay Gateway Order
      const initRes = await fetch('/api/payments/gateway/create-order', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${currentToken}`
        },
        body: JSON.stringify({ orderId: activeOrderForPayment.id, currency: 'INR' })
      });

      if (!initRes.ok) {
        const err = await initRes.json();
        showToast('Gateway order creation failed: ' + err.message, 'error');
        btn.disabled = false;
        btn.innerText = 'Pay Now';
        return;
      }

      const gatewayOrder = await initRes.json();
      showToast(`Gateway Order ${gatewayOrder.gatewayOrderId} created. Verifying cryptographic signature...`, 'info');

      // Step 2: Simulate client signature (computes SHA-256 HMAC)
      const payId = 'pay_' + Math.random().toString(36).substring(2, 14);
      const signaturePayload = `${gatewayOrder.gatewayOrderId}|${payId}`;

      // In real browser we can use WebCrypto or server-backed verification
      const verifyRes = await fetch('/api/payments/gateway/verify', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${currentToken}`
        },
        body: JSON.stringify({
          orderId: activeOrderForPayment.id,
          razorpayOrderId: gatewayOrder.gatewayOrderId,
          razorpayPaymentId: payId,
          razorpaySignature: simulateFailure ? 'invalid_signature_fail' : await computeHmacSha256(signaturePayload, 'OrderFlowRazorpaySecretKey2026')
        })
      });

      const verifyData = await verifyRes.json();
      if (!verifyRes.ok) {
        showToast(verifyData.message || 'Signature verification failed!', 'error');
        return;
      }

      showToast(`Razorpay Payment Verified! Order #${activeOrderForPayment.orderNumber} confirmed.`, 'success');
      navigate('/profile/orders');
    } else {
      // Standard Direct Gateway Simulation / COD
      const res = await fetch('/api/payments', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${currentToken}`
        },
        body: JSON.stringify({
          orderId: activeOrderForPayment.id,
          paymentMethod: selectedPaymentMethod,
          simulateFailure: simulateFailure
        })
      });

      const data = await res.json();
      if (!res.ok) {
        showToast(data.message || 'Payment failed.', 'error');
        return;
      }

      if (data.status === 'COMPLETED' || data.status === 'PENDING') {
        showToast(`Payment processed successfully! Txn: ${data.transactionReference}`, 'success');
        navigate('/profile/orders');
      } else {
        showToast('Payment was rejected. Compensating cancellation saga triggered.', 'error');
        navigate('/profile/orders');
      }
    }
  } catch (err) {
    showToast('Payment execution error: ' + err.message, 'error');
  } finally {
    btn.disabled = false;
    btn.innerText = 'Pay Now';
  }
}

// Client-side WebCrypto HMAC-SHA256 Helper
async function computeHmacSha256(message, secret) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw', enc.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false, ['sign']
  );
  const signature = await crypto.subtle.sign('HMAC', key, enc.encode(message));
  return Array.from(new Uint8Array(signature)).map(b => b.toString(16).padStart(2, '0')).join('');
}

// Orders & Tracking View
async function loadOrders() {
  if (!requireAuth()) return;

  try {
    const res = await fetch('/api/orders/my-orders', {
      headers: { 'Authorization': `Bearer ${currentToken}` }
    });

    if (!res.ok) return;
    const data = await res.json();
    const orders = (data && data.content) ? data.content : (Array.isArray(data) ? data : []);
    renderOrdersList(orders);
  } catch (e) {
    console.error('Failed to load orders:', e);
  }
}

let cachedOrders = [];
function renderOrdersList(orders) {
  cachedOrders = orders || [];
  const container = document.getElementById('ordersContainer');
  if (!container) return;

  if (cachedOrders.length === 0) {
    container.innerHTML = '<div class="empty-state">No orders placed yet. Explore the catalog to make your first purchase.</div>';
    return;
  }

  container.innerHTML = cachedOrders.map(order => {
    const isAwaiting = order.status === 'AWAITING_PAYMENT';
    const isConfirmed = order.status === 'CONFIRMED';
    const isShipped = order.status === 'SHIPPED';
    const isDelivered = order.status === 'COMPLETED';
    const isCancelled = order.status === 'CANCELLED';

    const step1 = true;
    const step2 = isConfirmed || isShipped || isDelivered;
    const step3 = isShipped || isDelivered;
    const step4 = isDelivered;

    return `
      <div class="order-card">
        <div class="order-top">
          <div>
            <div class="order-num">Order #${order.orderNumber}</div>
            <div style="font-size:0.75rem;color:var(--text-dim);">${new Date(order.createdAt).toLocaleString()}</div>
          </div>
          <div style="display:flex;align-items:center;gap:0.75rem;">
            <span class="badge ${getStatusBadgeClass(order.status)}">${order.status}</span>
            <span style="font-weight:800;font-size:1.15rem;color:#fff;">₹${Number(order.totalAmount).toFixed(2)}</span>
          </div>
        </div>

        ${!isCancelled ? `
          <div class="order-timeline-stepper">
            <div class="step-node ${step1 ? 'active' : ''}">
              <div class="step-circle">1</div>
              <span class="step-label">Placed</span>
            </div>
            <div class="step-node ${step2 ? 'active' : ''}">
              <div class="step-circle">2</div>
              <span class="step-label">Paid</span>
            </div>
            <div class="step-node ${step3 ? 'active' : ''}">
              <div class="step-circle">3</div>
              <span class="step-label">Dispatched</span>
            </div>
            <div class="step-node ${step4 ? 'active' : ''}">
              <div class="step-circle">4</div>
              <span class="step-label">Delivered</span>
            </div>
          </div>
        ` : `
          <div style="padding:0.75rem;background:rgba(239,68,68,0.1);border:1px solid rgba(239,68,68,0.2);border-radius:var(--radius-sm);color:var(--danger);font-size:0.85rem;margin:1rem 0;">
            This order was cancelled. Reserved inventory was released and any payment automatically refunded.
          </div>
        `}

        <div style="display:flex;justify-content:space-between;align-items:center;margin-top:1rem;flex-wrap:wrap;gap:0.5rem;">
          <div style="font-size:0.85rem;color:var(--text-muted);">
            Destination: ${order.shippingAddress ? `${order.shippingAddress.streetAddress}, ${order.shippingAddress.city}` : 'Default Shipping'}
          </div>
          <div style="display:flex;gap:0.5rem;">
            ${isAwaiting ? `
              <button class="btn-primary" onclick="navigate('/checkout/${order.id}/pay')">Pay Now</button>
            ` : ''}
            ${(!isDelivered && !isCancelled) ? `
              <button class="btn-secondary" onclick="cancelOrder(${order.id})">Cancel Order</button>
            ` : ''}
          </div>
        </div>
      </div>
    `;
  }).join('');
}

function filterOrdersByStatus(filter) {
  document.querySelectorAll('.filter-tabs .tab-btn').forEach(btn => {
    btn.classList.toggle('active', btn.innerText.toUpperCase().includes(filter));
  });

  if (filter === 'ALL') {
    renderOrdersList(cachedOrders);
  } else if (filter === 'ACTIVE') {
    renderOrdersList(cachedOrders.filter(o => ['AWAITING_PAYMENT', 'CONFIRMED', 'SHIPPED'].includes(o.status)));
  } else if (filter === 'COMPLETED') {
    renderOrdersList(cachedOrders.filter(o => o.status === 'COMPLETED'));
  } else if (filter === 'CANCELLED') {
    renderOrdersList(cachedOrders.filter(o => o.status === 'CANCELLED'));
  }
}

async function cancelOrder(orderId) {
  if (!confirm('Are you sure you want to cancel this order? Stock will be restored and payment refunded.')) return;

  try {
    const res = await fetch(`/api/orders/${orderId}/cancel`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${currentToken}` }
    });
    if (res.ok) {
      showToast(`Order #${orderId} cancelled.`, 'info');
      loadOrders();
    } else {
      const err = await res.json();
      showToast(err.message || 'Cancellation failed.', 'error');
    }
  } catch (err) {
    showToast('Failed to cancel order: ' + err.message, 'error');
  }
}

function getStatusBadgeClass(status) {
  switch (status) {
    case 'CONFIRMED': return 'category-badge';
    case 'SHIPPED': return 'seller-badge';
    case 'COMPLETED': return 'in-stock';
    case 'CANCELLED': return 'out-of-stock';
    default: return 'low-stock';
  }
}

// User Profile & Addresses
function loadProfileView() {
  if (!currentUser) return;
  document.getElementById('profFullName').innerText = currentUser.fullName || 'Customer';
  document.getElementById('profEmail').innerText = currentUser.email;
  document.getElementById('profAvatar').innerText = (currentUser.fullName || currentUser.email).charAt(0).toUpperCase();
  document.getElementById('profRole').innerText = currentUser.role || 'ROLE_CUSTOMER';

  loadUserAddresses();
}

async function loadUserAddresses() {
  try {
    const res = await fetch('/api/users/me/addresses', {
      headers: { 'Authorization': `Bearer ${currentToken}` }
    });
    if (!res.ok) return;
    userAddresses = await res.json();

    const container = document.getElementById('addressesContainer');
    if (userAddresses.length === 0) {
      container.innerHTML = '<div class="empty-state">No saved addresses. Click "Add New Address" above.</div>';
      return;
    }

    container.innerHTML = userAddresses.map(a => `
      <div class="sidebar-card">
        <div style="display:flex;justify-content:space-between;align-items:center;">
          <strong>${a.recipientName}</strong>
          ${a.isDefault ? '<span class="verified-tag">Default</span>' : ''}
        </div>
        <div style="color:var(--text-muted);font-size:0.85rem;margin-top:0.35rem;">
          ${a.streetAddress}<br>
          ${a.city}, ${a.state} ${a.postalCode}, ${a.country}
        </div>
        <div style="font-size:0.75rem;color:var(--text-dim);margin-top:0.25rem;">Phone: ${a.phoneNumber}</div>
      </div>
    `).join('');
  } catch (e) {}
}

function toggleAddAddressForm() {
  const box = document.getElementById('addAddressBox');
  box.style.display = box.style.display === 'none' ? 'block' : 'none';
}

async function handleAddressSubmit(e) {
  e.preventDefault();
  if (!requireAuth()) return;

  const body = {
    recipientName: document.getElementById('addrRecipient').value.trim(),
    phoneNumber: document.getElementById('addrPhone').value.trim(),
    streetAddress: document.getElementById('addrStreet').value.trim(),
    city: document.getElementById('addrCity').value.trim(),
    state: document.getElementById('addrState').value.trim(),
    postalCode: document.getElementById('addrPostal').value.trim(),
    country: document.getElementById('addrCountry').value.trim(),
    isDefault: document.getElementById('addrIsDefault').checked
  };

  try {
    const res = await fetch('/api/users/me/addresses', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${currentToken}`
      },
      body: JSON.stringify(body)
    });

    if (res.ok) {
      showToast('Address saved successfully.', 'success');
      document.getElementById('addressForm').reset();
      toggleAddAddressForm();
      loadUserAddresses();
    } else {
      const err = await res.json();
      showToast(err.message || 'Failed to save address.', 'error');
    }
  } catch (err) {
    showToast('Failed to save address: ' + err.message, 'error');
  }
}

// Seller Hub
async function loadSellerDashboard() {
  if (!requireRole(['ROLE_SELLER', 'ROLE_ADMIN'])) return;

  try {
    // Load profile
    const profRes = await fetch('/api/seller/profile', {
      headers: { 'Authorization': `Bearer ${currentToken}` }
    });
    if (profRes.ok) {
      const seller = await profRes.json();
      document.getElementById('sellerStoreName').innerText = `${seller.businessName || 'Merchant Hub'}`;
    }

    // Load products
    const prodRes = await fetch('/api/seller/products', {
      headers: { 'Authorization': `Bearer ${currentToken}` }
    });
    if (prodRes.ok) {
      const prods = await prodRes.json();
      renderSellerProductsTable(prods);
      document.getElementById('sellerStatProducts').innerText = prods.length;
    }

    // Load orders
    const ordRes = await fetch('/api/seller/orders', {
      headers: { 'Authorization': `Bearer ${currentToken}` }
    });
    if (ordRes.ok) {
      const ords = await ordRes.json();
      renderSellerOrdersTable(ords);
      document.getElementById('sellerStatItems').innerText = ords.length;
      const rev = ords.reduce((acc, i) => acc + (Number(i.subtotal) || 0), 0);
      document.getElementById('sellerStatRevenue').innerText = `₹${rev.toFixed(2)}`;
    }
  } catch (e) {
    console.error('Failed to load seller hub:', e);
  }
}

function switchSellerTab(tab) {
  document.getElementById('tabSellerProducts').classList.toggle('active', tab === 'products');
  document.getElementById('tabSellerOrders').classList.toggle('active', tab === 'orders');
  document.getElementById('sellerProductsPanel').style.display = tab === 'products' ? 'block' : 'none';
  document.getElementById('sellerOrdersPanel').style.display = tab === 'orders' ? 'block' : 'none';
}

let cachedSellerProducts = [];

function renderSellerProductsTable(products) {
  cachedSellerProducts = products || [];
  const tbody = document.getElementById('sellerProductsTableBody');
  if (!products || products.length === 0) {
    tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-dim);">No products created under this store yet. Click "+ Add New Product" above.</td></tr>';
    return;
  }

  tbody.innerHTML = products.map(p => `
    <tr>
      <td><strong>${p.name}</strong></td>
      <td><code>${p.sku}</code></td>
      <td>${p.category || 'General'}</td>
      <td>₹${Number(p.price).toFixed(2)}</td>
      <td>${p.availableStock || 0}</td>
      <td>
        <button class="text-btn" onclick="openSellerProductForm(${p.id})">Edit</button>
        <span style="color:var(--text-dim);">·</span>
        <button class="text-btn" style="color:var(--danger);" onclick="deleteSellerProduct(${p.id})">Delete</button>
      </td>
    </tr>
  `).join('');
}

function renderSellerOrdersTable(orders) {
  const tbody = document.getElementById('sellerOrdersTableBody');
  if (!orders || orders.length === 0) {
    tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-dim);">No customer orders placed for your inventory yet.</td></tr>';
    return;
  }

  tbody.innerHTML = orders.map(o => `
    <tr>
      <td><strong>#${o.orderNumber}</strong></td>
      <td>${o.customerEmail}</td>
      <td>${o.productName} <span style="font-size:0.75rem;color:var(--text-dim);">(${o.sku})</span></td>
      <td>${o.quantity}</td>
      <td>₹${Number(o.subtotal).toFixed(2)}</td>
      <td><span class="badge ${getStatusBadgeClass(o.orderStatus)}">${o.orderStatus}</span></td>
    </tr>
  `).join('');
}

function openSellerProductForm(productId = null) {
  const card = document.getElementById('sellerProductFormCard');
  card.style.display = 'block';

  if (productId) {
    document.getElementById('sellerFormTitle').innerText = 'Edit Catalog Product';
    document.getElementById('sellerProductId').value = productId;
    const p = cachedSellerProducts.find(x => x.id === productId);
    if (p) {
      document.getElementById('sellerSku').value = p.sku || '';
      document.getElementById('sellerName').value = p.name || '';
      document.getElementById('sellerPrice').value = p.price || '';
      document.getElementById('sellerCategory').value = p.category || 'Peripherals';
      document.getElementById('sellerStock').value = p.availableStock || 0;
      document.getElementById('sellerDesc').value = p.description || '';
      document.getElementById('sellerImageUrl').value = p.imageUrl || '';
    }
  } else {
    document.getElementById('sellerFormTitle').innerText = 'Create New Catalog Product';
    document.getElementById('sellerProductId').value = '';
    document.getElementById('sellerProductForm').reset();
    document.getElementById('sellerStock').value = '25';
  }
}

function closeSellerProductForm() {
  document.getElementById('sellerProductFormCard').style.display = 'none';
}

async function handleSellerProductSubmit(e) {
  e.preventDefault();
  if (!requireRole(['ROLE_SELLER', 'ROLE_ADMIN'])) return;

  const id = document.getElementById('sellerProductId').value;
  const sku = document.getElementById('sellerSku').value.trim();
  const name = document.getElementById('sellerName').value.trim();
  const price = parseFloat(document.getElementById('sellerPrice').value);
  const category = document.getElementById('sellerCategory').value;
  const initialStock = parseInt(document.getElementById('sellerStock').value);
  const description = document.getElementById('sellerDesc').value.trim();
  const imageUrl = document.getElementById('sellerImageUrl').value.trim() || 'https://images.unsplash.com/photo-1587829741301-dc798b83add3?w=500&q=80';

  const body = { sku, name, price, category, initialStock, description, imageUrl };
  const method = id ? 'PUT' : 'POST';
  const url = id ? `/api/seller/products/${id}` : '/api/seller/products';

  try {
    const res = await fetch(url, {
      method,
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${currentToken}`
      },
      body: JSON.stringify(body)
    });

    if (res.ok) {
      showToast(`Product ${id ? 'updated' : 'created'} successfully!`, 'success');
      closeSellerProductForm();
      loadSellerDashboard();
    } else {
      const err = await res.json().catch(() => ({}));
      let msg = err.message;
      if (!msg && err.details) {
        msg = Object.entries(err.details).map(([k, v]) => `${k}: ${v}`).join('; ');
      }
      showToast(msg || err.error || 'Failed to save product.', 'error');
    }
  } catch (err) {
    showToast('Failed to save product: ' + err.message, 'error');
  }
}

async function deleteSellerProduct(id) {
  if (!confirm('Are you sure you want to delete this product? Associated inventory will be purged.')) return;

  try {
    const res = await fetch(`/api/seller/products/${id}`, {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${currentToken}` }
    });
    if (res.ok) {
      showToast('Product deleted.', 'info');
      loadSellerDashboard();
    } else {
      const err = await res.json();
      showToast(err.message || 'Deletion failed.', 'error');
    }
  } catch (err) {
    showToast('Deletion error: ' + err.message, 'error');
  }
}

// Real-time Notifications via Server-Sent Events (SSE) with Polling Fallback
let notifEventSource = null;

function startNotificationPolling() {
  stopNotificationPolling();
  if (!currentToken) return;

  // Immediate initial load of existing notifications
  fetchNotifications();

  // Establish SSE stream for instant push
  try {
    const streamUrl = `/api/notifications/stream?token=${encodeURIComponent(currentToken)}`;
    notifEventSource = new EventSource(streamUrl);

    notifEventSource.addEventListener('NOTIFICATION', (event) => {
      try {
        const notif = JSON.parse(event.data);
        handleIncomingNotification(notif);
      } catch (err) {
        console.error("Error processing SSE notification:", err);
      }
    });

    notifEventSource.addEventListener('CONNECTED', () => {
      console.log("Real-time notifications stream active via SSE.");
    });

    notifEventSource.onerror = (err) => {
      console.warn("SSE connection interrupted, falling back to background polling", err);
      if (notifEventSource) {
        notifEventSource.close();
        notifEventSource = null;
      }
      if (!notifInterval) {
        notifInterval = setInterval(fetchNotifications, 8000);
      }
    };
  } catch (e) {
    console.error("EventSource initialization failed, using polling fallback:", e);
    notifInterval = setInterval(fetchNotifications, 5000);
  }
}

function handleIncomingNotification(n) {
  // Update badge
  const badge = document.getElementById('notifBadge');
  if (badge) {
    const count = parseInt(badge.innerText || '0', 10) + 1;
    badge.innerText = count;
    badge.style.display = 'inline-block';
  }

  // Prepend to notifications list
  const list = document.getElementById('notifList');
  if (list) {
    const emptyMsg = list.querySelector('.empty-notif');
    if (emptyMsg) {
      list.innerHTML = '';
    }
    const itemHtml = `
      <div class="notif-item unread" onclick="markNotificationRead(${n.id}, ${n.orderId})">
        <div class="notif-title">${n.title}</div>
        <div class="notif-msg">${n.message}</div>
        <div class="notif-time">${new Date(n.createdAt || Date.now()).toLocaleTimeString()}</div>
      </div>
    `;
    list.insertAdjacentHTML('afterbegin', itemHtml);
  }

  // Trigger real-time popup toast
  showToast(`${n.title}: ${n.message}`, 'info');
}

function stopNotificationPolling() {
  if (notifEventSource) {
    try {
      notifEventSource.close();
    } catch (e) {}
    notifEventSource = null;
  }
  if (notifInterval) {
    clearInterval(notifInterval);
    notifInterval = null;
  }
}

async function fetchNotifications() {
  if (!currentToken) return;

  try {
    const res = await fetch('/api/notifications', {
      headers: { 'Authorization': `Bearer ${currentToken}` }
    });
    if (!res.ok) return;

    const notifs = await res.json();
    const unreadCount = notifs.filter(n => !n.isRead).length;

    const badge = document.getElementById('notifBadge');
    if (badge) {
      badge.innerText = unreadCount;
      badge.style.display = unreadCount > 0 ? 'inline-block' : 'none';
    }

    const list = document.getElementById('notifList');
    if (list) {
      if (notifs.length === 0) {
        list.innerHTML = '<div class="empty-notif">No notifications yet</div>';
      } else {
        list.innerHTML = notifs.map(n => `
          <div class="notif-item ${!n.isRead ? 'unread' : ''}" onclick="markNotificationRead(${n.id}, ${n.orderId})">
            <div class="notif-title">${n.title}</div>
            <div class="notif-msg">${n.message}</div>
            <div class="notif-time">${new Date(n.createdAt).toLocaleTimeString()}</div>
          </div>
        `).join('');
      }
    }
  } catch (e) {}
}

function toggleNotifications() {
  const drop = document.getElementById('notifDropdown');
  drop.style.display = drop.style.display === 'none' ? 'block' : 'none';
}

async function markNotificationRead(id, orderId) {
  try {
    await fetch(`/api/notifications/${id}/read`, {
      method: 'PATCH',
      headers: { 'Authorization': `Bearer ${currentToken}` }
    });
    fetchNotifications();
    if (orderId) {
      navigate('/profile/orders');
    }
  } catch (e) {}
}

async function markAllNotificationsRead() {
  try {
    await fetch('/api/notifications/read-all', {
      method: 'PATCH',
      headers: { 'Authorization': `Bearer ${currentToken}` }
    });
    fetchNotifications();
  } catch (e) {}
}

// Telemetry Polling (Admin Only)
function startTelemetryPolling() {
  stopTelemetryPolling();
  if (!currentUser || currentUser.role !== 'ROLE_ADMIN') return;
  fetchTelemetry();
  telemetryInterval = setInterval(fetchTelemetry, 2000);
}

function stopTelemetryPolling() {
  if (telemetryInterval) clearInterval(telemetryInterval);
}

async function fetchTelemetry() {
  if (!currentToken || !currentUser || currentUser.role !== 'ROLE_ADMIN') return;

  try {
    const res = await fetch('/api/events/recent', {
      headers: { 'Authorization': `Bearer ${currentToken}` }
    });

    if (!res.ok) return;
    const events = await res.json();
    const feed = document.getElementById('eventFeed');
    if (!feed) return;

    if (!events || events.length === 0) {
      feed.innerHTML = '<div class="empty-feed">No Kafka events captured yet in this session.</div>';
      return;
    }

    feed.innerHTML = events.map(ev => `
      <div class="event-entry">
        <span class="event-time">${new Date(ev.timestamp).toLocaleTimeString()}</span>
        <span class="event-tag">[${ev.eventType}]</span>
        <div style="color:var(--text-muted);margin-top:0.25rem;">
          ${JSON.stringify(ev.payload || ev)}
        </div>
      </div>
    `).join('');
  } catch (e) {}
}

// Toast Notifications
function showToast(message, type = 'info') {
  const container = document.getElementById('toastContainer');
  if (!container) return;

  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.innerText = message;
  container.appendChild(toast);

  setTimeout(() => {
    toast.style.opacity = '0';
    setTimeout(() => toast.remove(), 300);
  }, 3500);
}
