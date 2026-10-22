import { Panel } from '@/components/catalog-shared'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Catalog } from '@/pages/Catalog'
import { HealthPage } from '@/pages/HealthPage'
import { Labels } from '@/pages/Labels'
import { Materials } from '@/pages/Materials'
import { Overview } from '@/pages/Overview'
import { WorkspaceOverview } from '@/auth/WorkspaceOverview'
import { signOut, type Identity } from '@/auth/session'
import { ImpactInbox } from '@/features/impact/ImpactInbox'
import { LabelWorkspace } from '@/features/labels/LabelWorkspace'
import { GatewayProducts, GatewaySuppliers, GatewayMaterials } from '@/features/catalog/GatewayCatalog'
import {
  Activity,
  Box,
  ChevronRight,
  CircleHelp,
  ClipboardCheck,
  FileText,
  FlaskConical,
  GitBranch,
  Layers3,
  LayoutDashboard,
  Menu,
  Package,
  Search,
  ShieldCheck,
  Truck,
  X,
} from 'lucide-react'
import { useEffect, useState } from 'react'
import {
  NavLink,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from 'react-router-dom'
const navigation = [
  { path: '/', label: 'Overview', icon: LayoutDashboard },
  { path: '/suppliers', label: 'Suppliers', icon: Truck },
  { path: '/materials', label: 'Materials & specs', icon: Layers3 },
  { path: '/products', label: 'Products', icon: Package },
  { path: '/formulas', label: 'Formula versions', icon: FlaskConical },
  { path: '/labels', label: 'Labels', icon: FileText },
  { path: '/impact', label: 'Change impact', icon: GitBranch },
  { path: '/reviews', label: 'Review workspace', icon: ClipboardCheck },
]
import { CatalogConnection } from '@/components/CatalogConnection'
export function Shell({ identity }: { identity?: Identity }) {
  const [mobile, setMobile] = useState(false)
  const [help, setHelp] = useState(false)
  const location = useLocation()
  const navigate = useNavigate()
  useEffect(() => {
    setMobile(false)
  }, [location.pathname])
  const active = navigation.find((n) => n.path === location.pathname)
  const scope = `${identity?.subject}:${identity?.organisationId}:${location.pathname}:${location.search}`
  const labelId = new URLSearchParams(location.search).get('label') || ''
  const needsSignIn = <Panel><h1>Sign in required</h1><p>Use the authenticated workspace to access impact, review and publication workflows.</p></Panel>
  return (
    <div className="app-shell">
      <a href="#main" className="skip-link">
        Skip to main content
      </a>
      <header className="topbar">
        <div className="wordmark">
          <span className="logo">
            <Layers3 size={23} />
          </span>
          SpecTrace<span className="workspace-label">Food label workspace</span>
        </div>
        <div className="topbar-right">
          <span className="environment">
            <span />
            {identity ? 'Authenticated workspace' : 'Local workspace'}
          </span>
          <button aria-label="Help" onClick={() => setHelp(true)}>
            <CircleHelp size={19} />
          </button>
          <span className="topbar-divider" />
          {identity ? <Button className="session-signout" variant="outline" onClick={() => void signOut()}>Sign out</Button> : <span className="avatar">XF</span>}
        </div>
      </header>
      {mobile && (
        <button
          className="mobile-overlay"
          aria-label="Dismiss navigation overlay"
          onClick={() => setMobile(false)}
        />
      )}
      <aside className={`sidebar ${mobile ? 'is-open' : ''}`}>
        <div className="workspace-switch">
          <span className="workspace-icon">
            <Box size={21} />
          </span>
          <div>
            <strong>FoodLabelFlow</strong>
            <small>TEAM 16 · SWE5001</small>
          </div>
          <button
            className="mobile-close"
            aria-label="Close navigation"
            onClick={() => setMobile(false)}
          >
            <X size={18} />
          </button>
        </div>
        <p className="nav-section-label">Workspace</p>
        <nav aria-label="Main navigation">
          {navigation.map((n, i) => (
            <NavLink
              end={n.path === '/'}
              to={n.path}
              key={n.path}
              className={({ isActive }) =>
                `nav-item ${isActive ? 'active' : ''}`
              }
            >
              <span className="nav-num" aria-hidden="true">
                {String(i + 1).padStart(2, '0')}
              </span>
              <n.icon size={18} />
              <span>{n.label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="version-note">
            <span className="version-icon">
              <ShieldCheck size={21} />
            </span>
            <strong>Always traceable</strong>
            <p>Preserve history. Keep every change in view.</p>
            <div className="barcode" aria-hidden="true" />
            <span className="barcode-caption" aria-hidden="true">
              LOT 26·T16 · SWE5001
            </span>
          </div>
          <NavLink
            to="/health"
            className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
          >
            <Activity size={18} />
            <span>System status</span>
          </NavLink>
          <button className="nav-item" onClick={() => setHelp(true)}>
            <CircleHelp size={18} />
            <span>User guide</span>
          </button>
          <div className="user-card">
            <span className="avatar">{identity ? <ShieldCheck size={20} aria-hidden="true" /> : 'XF'}</span>
            <div>
              <strong>{identity ? 'Signed-in account' : 'Xu Feiyang'}</strong>
              <small title={identity?.subject}>{identity ? `${identity.organisationType} · ${identity.subject.slice(0, 8)}…` : 'M3 · Preview profile'}</small>
            </div>
          </div>
        </div>
      </aside>
      <div className="main-shell">
        <div className="contextbar">
          <div className="breadcrumb">
            <button
              className="mobile-toggle"
              aria-label="Open navigation"
              onClick={() => setMobile(true)}
            >
              <Menu size={20} />
            </button>
            <span>Workspace</span>
            <ChevronRight size={14} />
            <strong>
              {active?.label ??
                (location.pathname === '/health' ? 'System status' : 'Page')}
            </strong>
          </div>
          <button
            className="quick-search"
            aria-label="Find products and formulas"
            onClick={() => navigate('/products')}
          >
            <Search size={16} />
            <span>Find products and formulas</span>
          </button>
        </div>
        <main id="main" tabIndex={-1}>
          <Routes>
            <Route path="/" element={identity ? <WorkspaceOverview key={scope} identity={identity} /> : <CatalogConnection><Overview /></CatalogConnection>} />
            <Route path="/products" element={identity ? <GatewayProducts key={scope} identity={identity} /> : <CatalogConnection><Catalog /></CatalogConnection>} />
            <Route path="/formulas" element={identity ? <GatewayProducts key={scope} identity={identity} formulas /> : <CatalogConnection><Catalog formulas /></CatalogConnection>} />
            <Route path="/suppliers" element={identity ? <GatewaySuppliers key={scope} identity={identity} /> : <CatalogConnection><Materials suppliers /></CatalogConnection>} />
            <Route path="/materials" element={identity ? <GatewayMaterials key={scope} identity={identity} /> : <CatalogConnection><Materials /></CatalogConnection>} />
            <Route path="/labels" element={identity ? <LabelWorkspace key={scope} identity={identity} initialLabelId={labelId} /> : <CatalogConnection><Labels /></CatalogConnection>} />
            <Route path="/impact" element={identity ? <ImpactInbox key={scope} identity={identity} openLabel={id => navigate(`/reviews?label=${encodeURIComponent(id)}`)} /> : needsSignIn} />
            <Route path="/reviews" element={identity ? <LabelWorkspace key={scope} identity={identity} initialLabelId={labelId} reviewOnly /> : needsSignIn} />
            <Route path="/health" element={identity ? <WorkspaceOverview key={scope} identity={identity} statusOnly /> : <HealthPage />} />
            <Route
              path="*"
              element={
                <Panel className="upcoming">
                  <h1>Page not found</h1>
                  <Button onClick={() => navigate('/')}>Back to overview</Button>
                </Panel>
              }
            />
          </Routes>
          <footer className="page-footer">
            <span>SpecTrace · Food label change control</span>
            <span>{identity ? 'Authenticated workspace' : 'Local workspace'} / Single-market MVP</span>
          </footer>
        </main>
      </div>
      <Dialog open={help} onOpenChange={setHelp}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Welcome to SpecTrace</DialogTitle>
            <DialogDescription>
              Browse products, materials and formula traceability.
            </DialogDescription>
          </DialogHeader>
          <ol className="help-list">
            <li>Search by product name, brand or ID, then select a product to view its formula.</li>
            <li>Use the detail tabs to view formula items, version history and sources.</li>
            <li>Browse suppliers and materials to inspect specifications and components.</li>
            <li>{identity ? 'Catalog pages use your signed-in organisation and the public Gateway. Failed requests do not fall back to preview data.' : 'Catalog pages read from the local M1 API. Failed requests never fall back to offline data.'}</li>
            <li>{identity ? 'Use Change impact to inspect findings. Open an exact label version in Review workspace to approve, request changes, reject or publish according to your roles.' : 'Local preview is not a signed-in account. Production workflows require the authenticated workspace.'}</li>
          </ol>
        </DialogContent>
      </Dialog>
    </div>
  )
}
