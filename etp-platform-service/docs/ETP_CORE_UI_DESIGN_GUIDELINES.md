# ETP Core UI Design System V1

**Authoritative source of truth** for all future ETP Core UI implementation.

| | |
|---|---|
| **Status** | V1 — documentation only (no React implementation in this deliverable) |
| **Scope** | ETP Core UI design system, shell, and interaction patterns |
| **Related docs** | [CAPABILITY_REGISTRY.md](CAPABILITY_REGISTRY.md), [ARCHITECTURE.md](../ARCHITECTURE.md), [API_CONVENTIONS.md](API_CONVENTIONS.md) |

---

## Visual references

Two approved reference images inform **visual language and layout**, not functional architecture:

| Reference | Use for |
|-----------|---------|
| **Approved ETP Login Screen** | Branding, logo treatment, brand colors, typography hierarchy, visual density, overall visual identity |
| **ETP Core Shell Reference** | Header structure, sidebar structure, authenticated layout, content area composition |

**Precedence:** ETP architecture and this document take precedence over anything shown in reference images. Do not copy unnecessary navigation, placeholder metrics, or undocumented functionality from the shell reference.

Reference images are **visual references only** — they are not specifications for routes, APIs, or feature scope.

---

## 1. Three ETP UI layers

ETP has three distinct UI layers with different ownership, audience, and deployment boundaries.

### A. ETP Core UI

**Audience:** Internal ETP platform team.

**Purpose:** Operate and evolve the platform.

| Responsibility | Examples |
|----------------|----------|
| Platform management | Platform health, configuration |
| Capability Registry | Register capabilities, versions, contracts, lifecycle |
| Application Registry | Customer applications and definitions (future) |
| Application Composition | Compose application definitions (future) |
| Tenant / environment administration | Tenant and environment context (future) |
| Platform operations | Operational views (future) |
| Audit | Audit exploration (future API) |
| Platform configuration | Non-secret platform settings |

ETP Core UI uses **this design system** and ETP Core branding.

### B. ETP Customer UI

**Audience:** Customer technical teams operating their ETP workspace.

**Purpose:** Configure and extend capabilities within customer boundaries.

| Responsibility | Examples |
|----------------|----------|
| Selected capabilities | Capability instances enabled for the customer |
| Capability configuration | Customer-specific capability settings |
| Customer business rules | Rules owned by the customer |
| Customer business logic | Logic owned by the customer |
| Customer application configuration | Application-level customer settings |

ETP Customer UI may use a **customer-specific design system** (see §27). It is not required to mirror ETP Core branding.

### C. Business Application UI

**Audience:** End users of a deployed business application.

**Purpose:** Execute business workflows and tasks in production.

| Characteristic | Detail |
|----------------|--------|
| Runtime input | Container images, deployable artifacts, runtime configuration, environment secrets |
| Source code | **Not** delivered to the runtime |
| Design | Follows the applicable **customer** design system |

---

## 2. Code ownership and deployment

### ETP Core owns

- Platform source code
- Reusable capability implementations
- Platform services
- Capability definitions (catalog metadata)
- Application composition framework (future)
- Build, release, and control mechanisms

### ETP Customer owns

- Customer-specific business rules
- Customer-specific business logic
- Customer-specific application configuration

### Business Application runtime receives

- Container images
- Deployable artifacts
- Runtime configuration
- Environment-specific configuration and secrets

**Source code must not be delivered to the Business Application runtime.**

### Conceptual flow

```
ETP CORE
   ↓
Application Composition
   ↓
ETP CUSTOMER
   ↓
Customer Configuration / Logic
   ↓
Build / Package
   ↓
Container Images
   ↓
Business Application Runtime
```

---

## 3. Capability architecture (UI model)

A **Capability** is a reusable ETP building block. The backend models it as a single **aggregate**; the UI must mirror that model.

```
CAPABILITY
    |
    +-- CAPABILITY_VERSION
            |
            +-- CONTRACT
            +-- IMPLEMENTATION
            +-- DEPENDENCIES
```

### UI rule: one aggregate experience

The Capability Registry UI treats **Overview, Versions, Contract, Implementation, and Dependencies** as sections of **one Capability detail experience** (tabs or equivalent), not as separate top-level platform areas.

**Do not** create separate top-level navigation for Versions, Contracts, Implementations, or Dependencies.

Align terminology and lifecycle behavior with [CAPABILITY_REGISTRY.md](CAPABILITY_REGISTRY.md).

---

## 4. Capabilities are not navigation items

**Hard architectural rule:** Capabilities must **not** automatically become ETP Core navigation items.

**Do not** create top-level Core navigation for:

- IAM
- Workflow
- Rules Engine
- API
- Event
- UI
- Database

These are **reusable capabilities** registered and managed through the **Capability Registry**.

```
ETP Core UI
   |
   +-- Capabilities
          |
          +-- etp.iam
          +-- etp.event
          +-- etp.rules
          +-- etp.workflow
          +-- etp.api
          +-- etp.ui
          +-- ...
```

Capability **codes** appear in registry lists and detail views — not as permanent sidebar roots.

---

## 5. Workflow vs Rules Engine

| | Workflow Capability | Rules Engine Capability |
|---|---------------------|-------------------------|
| Question | **What happens next?** | **What decision should be made?** |
| Responsibility | Orchestration, sequencing, tasks, process execution, state transitions | Business rules, conditions, decisioning, decision tables, rule evaluation |
| Relationship | May **consume** Rules Engine | Independently consumable when decisioning is needed outside workflow |

Other capabilities may also consume Rules Engine.

**Customer-specific** rules and decision tables belong to the **ETP Customer** layer, not ETP Core UI defaults.

---

## 6. Design system structure

```
ETP CORE DESIGN SYSTEM
|
+-- Brand
+-- Design Tokens
+-- Foundations
+-- Components
+-- Patterns
+-- Accessibility
+-- Content Guidelines
+-- Screen Guidelines
```

Future implementation must consume tokens and components from this structure — not ad hoc styles per screen.

---

## 7. Brand

| Attribute | Value |
|-----------|-------|
| **Product name** | ETP |
| **Full name** | Enterprise Technology Platform |
| **Tagline** | Compose. Extend. Integrate. Deliver. |
| **Supporting statement** | A unified platform for building, composing, and operating enterprise capabilities and applications. |

### Core principles (brand pillars)

| Pillar | Meaning |
|--------|---------|
| Capability Driven | Capabilities are the primary unit of reuse |
| API First | Contracts and APIs define boundaries |
| Event Driven | Events integrate capabilities asynchronously |
| Composable Architecture | Applications compose from capabilities |
| Multi-Application Platform | One platform serves many applications |

### Logo rules

- Use the **approved ETP logo** from the login reference (asset files — do not recreate with CSS/text).
- **Full logo:** Icon + “ETP” wordmark + “Enterprise Technology Platform” subline — login screen, marketing surfaces, unauthenticated flows.
- **Compact mark:** Icon + “ETP” — header, collapsed contexts, favicon source.
- **Variants:** Light-on-dark (login left panel, Core shell header/sidebar); dark-on-light only where background is white/neutral (e.g. print, light marketing).
- **Clear space:** Minimum padding equal to the height of the “E” in the wordmark on all sides.
- **Minimum size:** Full logo — 120px width minimum; compact mark — 32px icon height minimum.
- **Prohibited:** Distorting aspect ratio, unapproved colors, drop shadows on logo, substituting generic cubes/hexagons.

---

## 8. Design tokens

Design tokens are the **single source of truth** for visual values. Do not hard-code colors, spacing, or typography in feature code.

### Naming convention

Semantic tokens use the `--etp-*` prefix:

```
--etp-color-brand-primary
--etp-color-surface
--etp-color-text-primary
--etp-color-text-secondary
--etp-color-border
--etp-color-action-primary
--etp-color-success
--etp-color-warning
--etp-color-error
```

Map tokens in one theme layer (CSS custom properties, theme object, or design-token package). Components reference **semantic** tokens, not raw palette slots, except in the token definition file.

### Token categories

| Category | Examples |
|----------|----------|
| Colors | Brand, surface, text, border, action, semantic status |
| Typography | Font family, sizes, weights, line heights |
| Spacing | 4px-based scale |
| Border radius | sm, md, lg, xl, pill |
| Elevation | shadow-sm, shadow-md, shadow-lg |
| Motion | Duration, easing |
| Layout | Header height, sidebar widths, content padding |
| Breakpoints | sm, md, lg, xl |
| Component states | focus ring, disabled opacity |

---

## 9. Color system

Derived from the **approved login screen** and **Core shell reference**. Visual language: **clean, enterprise, restrained, professional**. Do not introduce arbitrary accent colors.

### Primary brand family

| Token | Hex (V1 reference) | Usage |
|-------|-------------------|--------|
| `--etp-color-brand-navy` | `#0B1220` | Shell header, sidebar, login left panel background |
| `--etp-color-brand-navy-elevated` | `#111827` | Sidebar hover, elevated nav surfaces |
| `--etp-color-brand-blue` | `#2563EB` | Primary actions, links, active nav accent, tagline accent |
| `--etp-color-brand-blue-hover` | `#1D4ED8` | Primary button hover |
| `--etp-color-brand-cyan` | `#38BDF8` | Icon accents, data/tech highlights (restrained) |
| `--etp-color-brand-purple` | `#7C3AED` | Gradient accent with blue (login tagline, hero illustration) |
| `--etp-color-brand-gradient` | `linear-gradient(90deg, #2563EB 0%, #7C3AED 100%)` | Marketing tagline only — not for dense app chrome |

*Hex values are V1 documentation targets; final implementation should match approved logo/asset exports.*

### Neutral family

| Token | Hex | Usage |
|-------|-----|--------|
| `--etp-color-white` | `#FFFFFF` | Cards, login form surface, primary content cards |
| `--etp-color-neutral-50` | `#F9FAFB` | Page background (content area) |
| `--etp-color-neutral-100` | `#F3F4F6` | Subtle fills, table header |
| `--etp-color-neutral-200` | `#E5E7EB` | Borders, dividers |
| `--etp-color-neutral-300` | `#D1D5DB` | Disabled borders |
| `--etp-color-neutral-400` | `#9CA3AF` | Placeholder text |
| `--etp-color-neutral-500` | `#6B7280` | Secondary text |
| `--etp-color-neutral-600` | `#4B5563` | Body text secondary |
| `--etp-color-neutral-700` | `#374151` | Strong secondary |
| `--etp-color-neutral-800` | `#1F2937` | Headings on light surfaces |
| `--etp-color-neutral-900` | `#111827` | Primary text on light surfaces |
| `--etp-color-neutral-950` | `#030712` | Maximum contrast text |

### Semantic colors

| Token | Hex | Usage |
|-------|-----|--------|
| `--etp-color-success` | `#16A34A` | Healthy, success badges, positive trends |
| `--etp-color-success-subtle` | `#DCFCE7` | Success background |
| `--etp-color-warning` | `#D97706` | Warning, caution |
| `--etp-color-warning-subtle` | `#FEF3C7` | Warning background |
| `--etp-color-error` | `#DC2626` | Errors, destructive emphasis |
| `--etp-color-error-subtle` | `#FEE2E2` | Error background |
| `--etp-color-info` | `#2563EB` | Informational (align with brand blue) |
| `--etp-color-info-subtle` | `#DBEAFE` | Info background |

### Semantic application tokens

| Token | Maps to | Usage |
|-------|---------|--------|
| `--etp-color-surface` | white / neutral-50 | Main content background |
| `--etp-color-surface-raised` | white | Cards, modals |
| `--etp-color-text-primary` | neutral-900 | Primary content text |
| `--etp-color-text-secondary` | neutral-500 | Supporting text |
| `--etp-color-text-on-dark` | white / neutral-200 | Text on navy surfaces |
| `--etp-color-border` | neutral-200 | Default borders |
| `--etp-color-action-primary` | brand-blue | Primary buttons |
| `--etp-color-focus-ring` | brand-blue at 40% opacity | Focus visibility |

### Color rules

- Color must **never** be the only mechanism for communicating state — pair with icon, label, or text.
- Status badges use **semantic token + label** (e.g. “Active”, not green dot alone).
- Avoid rainbow dashboards; one primary accent (blue) with restrained semantic colors.

---

## 10. Typography

### Font stack

```css
--etp-font-family: 'Inter', system-ui, -apple-system, sans-serif;
```

Load Inter via approved self-hosted or CDN strategy in implementation; do not substitute arbitrary fonts.

### Type scale (authenticated Core UI)

Avoid oversized marketing typography inside the Core shell. Login may use display scale; app chrome uses restrained headings.

| Role | Size | Weight | Line height | Usage |
|------|------|--------|-------------|--------|
| **Display** | 48px | 700 | 1.1 | Login tagline only |
| **H1** | 28px | 600 | 1.25 | Page title (e.g. “Dashboard”, “Capabilities”) |
| **H2** | 22px | 600 | 1.3 | Section titles, card group headers |
| **H3** | 18px | 600 | 1.35 | Subsection, tab labels emphasis |
| **H4** | 16px | 600 | 1.4 | Form section titles |
| **Body Large** | 16px | 400 | 1.5 | Intro paragraphs, empty states |
| **Body** | 14px | 400 | 1.5 | Default UI text, table cells |
| **Body Small** | 13px | 400 | 1.45 | Metadata, timestamps |
| **Label** | 12px | 500 | 1.4 | Form labels, table headers, nav section labels |
| **Caption** | 11px | 400 | 1.4 | Footnotes, legal, helper text |

### Typography rules

- **Page title:** One H1 per page via `ETPPageHeader`.
- **Monospace:** Use only for codes (`etp.iam`), JSON snippets, IDs — not body text.
- **Truncation:** Long capability names truncate with ellipsis; full value in tooltip.

---

## 11. Spacing

4px-based scale — **do not use arbitrary values**.

| Token | Value |
|-------|-------|
| `--etp-space-1` | 4px |
| `--etp-space-2` | 8px |
| `--etp-space-3` | 12px |
| `--etp-space-4` | 16px |
| `--etp-space-5` | 20px |
| `--etp-space-6` | 24px |
| `--etp-space-8` | 32px |
| `--etp-space-10` | 40px |
| `--etp-space-12` | 48px |
| `--etp-space-16` | 64px |

### Usage

| Context | Spacing |
|---------|---------|
| Page padding (content area) | `--etp-space-8` (32px) |
| Section gap (between major blocks) | `--etp-space-8` to `--etp-space-10` |
| Card padding | `--etp-space-6` (24px) |
| Form field gap | `--etp-space-4` (16px) |
| Label to input | `--etp-space-2` (8px) |
| Table cell padding | `--etp-space-3` vertical, `--etp-space-4` horizontal |
| Sidebar item padding | `--etp-space-3` vertical, `--etp-space-4` horizontal |
| Inline icon to text | `--etp-space-2` (8px) |

---

## 12. Border radius

| Token | Value | Usage |
|-------|-------|--------|
| `--etp-radius-sm` | 4px | Inputs, small buttons, badges |
| `--etp-radius-md` | 6px | Buttons, cards (default) |
| `--etp-radius-lg` | 8px | Modals, large cards |
| `--etp-radius-xl` | 12px | Marketing panels (login right panel optional) |
| `--etp-radius-pill` | 999px | Status badges, nav active pill |

Enterprise styling — restrained corners, not fully rounded containers.

---

## 13. Elevation

Prefer **borders, spacing, and contrast** over heavy shadows.

| Token | Definition | Usage |
|-------|------------|--------|
| `--etp-shadow-sm` | `0 1px 2px rgba(0,0,0,0.05)` | Subtle card lift |
| `--etp-shadow-md` | `0 4px 6px -1px rgba(0,0,0,0.08)` | Dropdowns, popovers |
| `--etp-shadow-lg` | `0 10px 15px -3px rgba(0,0,0,0.08)` | Modals, drawers |

Cards on content background: default **1px border** (`--etp-color-border`) + optional `shadow-sm`.

---

## 14. Motion

Restrained, functional motion only. Support `prefers-reduced-motion: reduce`.

| Context | Duration | Easing |
|---------|----------|--------|
| Hover (color, border) | 150ms | ease |
| Focus ring | 0ms (instant) | — |
| Sidebar expand/collapse | 200ms | ease-out |
| Dialog / drawer enter | 200ms | ease-out |
| Dialog / drawer leave | 150ms | ease-in |
| Loading spinner | continuous | linear |
| Toast enter/leave | 200ms | ease |

**No decorative animation** (floating logos, parallax, gratuitous page transitions).

---

## 15. Layout

### Primary target

Desktop enterprise application optimized for **16:9** displays. 16:9 is a **design target**, not a fixed viewport — UI must remain usable when resized.

### Core shell dimensions (initial)

| Element | Value |
|---------|-------|
| Header height | `64px` (`--etp-layout-header-height`) |
| Sidebar width (expanded) | `240px` (`--etp-layout-sidebar-width`) |
| Sidebar width (collapsed) | `72px` (`--etp-layout-sidebar-collapsed-width`) |
| Content padding | `32px` (`--etp-space-8`) |

### Layout primitives

| Primitive | Purpose |
|-----------|---------|
| **Page layout** | Header + sidebar + scrollable main |
| **Container** | Max-width optional for marketing; full-width for data tables |
| **Grid** | 12-column for dashboard summaries only when needed |
| **Stack** | Vertical rhythm for forms and detail sections |
| **Inline** | Toolbar actions, filters, breadcrumbs |

### Breakpoints

| Name | Min width | Behavior |
|------|-----------|----------|
| `sm` | 640px | Collapse secondary columns |
| `md` | 768px | Sidebar may collapse to icon-only |
| `lg` | 1024px | Default Core layout |
| `xl` | 1280px | Comfortable table density |
| `2xl` | 1536px | Optional max-width on marketing only |

### Responsive rules

- Tables: horizontal scroll within container before breaking layout.
- Sidebar: collapsible below `md`; overlay drawer acceptable on narrow widths.
- Header context selectors: truncate with tooltip; never overflow header.

---

## 16. Iconography

| Attribute | Value |
|-----------|--------|
| **Library** | Lucide (single library — do not mix) |
| **Navigation** | 20px, stroke 1.5px |
| **Buttons** | 16px inline with text, stroke 1.5px |
| **Status** | 16px, stroke 1.5px |
| **Decorative** | Prohibited — every icon must support comprehension or action |

Icons align to text baselines in buttons and nav items.

---

## 17. Component system

Reusable **ETP** components (prefix `ETP` in implementation). Every component documents applicable states:

`Default` · `Hover` · `Focus` · `Active/Pressed` · `Selected` · `Disabled` · `Loading` · `Error` · `Success`

### Initial component catalog

| Component | Purpose |
|-----------|---------|
| `ETPButton` | Primary, secondary, ghost, destructive actions |
| `ETPInput` | Text, email, password with icons |
| `ETPSelect` | Single select, searchable where needed |
| `ETPTextarea` | Multi-line text, JSON contract editing (future) |
| `ETPTable` | Sortable, selectable data tables |
| `ETPBadge` | Lifecycle and status labels |
| `ETPTabs` | Capability detail sections |
| `ETPModal` | Confirmations, focused tasks |
| `ETPDrawer` | Secondary detail, filters (optional) |
| `ETPToast` | Success/error feedback |
| `ETPCard` | Grouped content — use sparingly in data views |
| `ETPPageHeader` | Title, description, primary actions |
| `ETPBreadcrumb` | Hierarchy within aggregate views |
| `ETPFormSection` | Grouped form fields with title |
| `ETPSearch` | List search with clear affordance |
| `ETPFilter` | Filter chips / dropdown filters |
| `ETPLoadingState` | Page and section loading |
| `ETPEmptyState` | No data with guided action |
| `ETPErrorState` | Recoverable errors with retry |

Components consume **design tokens only** — no inline hex or arbitrary spacing.

---

## 18. Data-heavy UI patterns

ETP Core is an enterprise **operations** UI — prioritize tables, filters, and detail workflows over dashboard card grids.

### Patterns

| Pattern | Guidance |
|---------|----------|
| **Tables** | Primary list pattern; sticky header; row hover; compact density |
| **Search** | Top of list views; debounced; clear button |
| **Filtering** | Category, lifecycle status; expose backend query params |
| **Sorting** | Column headers where API supports ordering (future); client sort for static lists |
| **Pagination** | Page size 25/50; show total count |
| **Detail views** | Master-detail or full page with tabs — not orphan modals for primary content |
| **Forms** | Sectioned with `ETPFormSection`; inline validation |
| **Lifecycle actions** | Primary actions per state; confirm destructive/irreversible transitions |
| **Confirmation dialogs** | Deprecate, retire, cancel, activate (when replacing active version) |
| **Empty states** | Explain what to create and link to action |
| **Loading states** | Skeleton for tables; spinner for actions |
| **Error states** | API error code + user guidance (see Content) |
| **Audit information** | Show timestamp, actor when API available; link to audit (future) |

**Avoid** excessive dashboard summary cards unless the screen’s purpose is operational overview.

---

## 19. Accessibility

**Target:** WCAG 2.2 Level AA.

| Requirement | Implementation |
|-------------|----------------|
| Keyboard navigation | All interactive elements reachable; logical tab order |
| Focus | Visible focus ring (`--etp-color-focus-ring`); never `outline: none` without replacement |
| Semantic HTML | `nav`, `main`, `header`, `button`, `table` appropriately |
| Labels | Every input has visible or `aria-label`; icons alone are insufficient |
| Forms | Errors linked via `aria-describedby`; announce validation |
| Screen readers | Status changes announced (toast, live regions for async) |
| Color contrast | 4.5:1 body text; 3:1 large text and UI components |
| Non-color status | Badge text + icon for success/warning/error |
| Reduced motion | Honor `prefers-reduced-motion` |
| ARIA | Use when HTML semantics insufficient; avoid redundant roles |

Accessibility is **not** a post-implementation audit — bake into components from V1.

---

## 20. Content and UX writing

Tone: **concise enterprise terminology**.

| Prefer | Avoid |
|--------|--------|
| Create capability | Add New Capability Item |
| Activate | Perform Activation |
| Deprecate | Move to deprecated state |
| Cancel version | Cancel this version item |

### Error messages

Explain:

1. **What happened**
2. **Why** (when known from API `message`)
3. **What the user can do**

Example for `INVALID_LIFECYCLE_TRANSITION`:

> **Cannot activate this version.** Only validated versions can be activated, and the version must be higher than the current active version. Review version status or create a newer version.

Map API codes from [CAPABILITY_REGISTRY.md](CAPABILITY_REGISTRY.md) to user-facing copy in a central dictionary.

---

## 21. Login screen

The **approved login screenshot** is the authoritative visual reference for unauthenticated ETP identity.

### Establishes

- ETP brand, logo, colors, typography, visual density
- Split layout: brand panel (navy) + form panel (white)
- Tagline with blue-to-purple gradient treatment
- Value pillars (Capability Driven, API First, etc.) — **marketing only**, not app nav
- Primary “Sign In” and secondary “Sign in with SSO” pattern
- Language selector, remember me, forgot password, footer links

### Rules

- Login and Core Shell must feel like **one product**.
- **Do not** require Tenant or Environment selection on login unless future authentication architecture explicitly requires it.
- Tenant and Environment are **post-login context** (header selectors).

### Login vs Core density

Login may use Display typography and illustration; Core shell uses restrained H1/H2 scale.

---

## 22. ETP Core shell

Use the **Core Shell reference** for layout inspiration only.

### Structure

```
HEADER (64px)
   |
SIDEBAR (240px) + CONTENT (flex)
```

### Header

| Zone | Content |
|------|---------|
| Left | Compact logo + platform identity |
| Center | Tenant selector, Environment selector, current context label (when applicable) |
| Right | Notifications, Help, User/profile menu |

Context selectors use dark-friendly styling on navy header per reference.

### Sidebar

- Background: `--etp-color-brand-navy`
- Text: `--etp-color-text-on-dark`
- Active item: blue pill/highlight (reference mid-blue)
- Section labels: uppercase Label typography, muted
- **Minimal** — only implemented navigation (see §23)

### Content area

- Background: `--etp-color-neutral-50`
- Page structure: `ETPPageHeader` + primary content
- Cards on white with border — not a generic SaaS template

**The shell must not become a generic SaaS dashboard** — no gratuitous charts, fake metrics, or nav copied from the reference beyond documented scope.

---

## 23. Core navigation

**Initial navigation — implement only what exists today.**

```
HOME
  Dashboard          (placeholder until operational dashboard defined)

PLATFORM
  Capabilities       (Capability Registry — first registry feature)
```

### Future areas (document only — do not implement until backend exists)

```
APPLICATIONS
  Applications

DESIGN
  Application Composition

OPERATIONS
  Operations

ADMINISTRATION
  Tenants
  Users & Access
  Audit
  Settings
```

### Prohibited top-level navigation

Do **not** add as Core sidebar roots:

- IAM, Workflow, Rules Engine, API, Events, UI, Database

The shell reference shows expanded items (Version Management, Contracts, etc.) — **those are not separate nav items**; they belong inside Capability detail.

---

## 24. Capability Registry UI

First major Core UI feature after shell. Backend: [CAPABILITY_REGISTRY.md](CAPABILITY_REGISTRY.md).

### Capability list

| Element | Behavior |
|---------|----------|
| Search | By name, code |
| Filters | `category`, `status` (capability lifecycle) |
| Table | Code, name, category, capability status, active version summary, updated metadata |
| Primary action | Create capability |
| Row action | Open detail |

No DELETE — lifecycle managed via PATCH.

### Capability detail (aggregate)

Single experience with tabs or sections:

| Section | Content |
|---------|---------|
| **Overview** | Code, name, category, description, capability lifecycle, metadata |
| **Versions** | Version list, semver, version lifecycle, effective/deprecated dates |
| **Contract** | Inline JSON document viewer/editor (draft); external `location` |
| **Implementation** | Technology metadata per version |
| **Dependencies** | Version-aware dependency list |

Version-level contract/implementation/dependencies are scoped to **selected version** within this view.

### API alignment

| UI action | API |
|-----------|-----|
| Create | `POST /api/v1/capabilities` |
| List | `GET /api/v1/capabilities` |
| Detail | `GET /api/v1/capabilities/{id}` |
| Update metadata / lifecycle / versions | `PATCH /api/v1/capabilities/{id}` |

---

## 25. Capability lifecycle (UI)

Align strictly with backend rules. UI shows **only allowed** actions per state.

### Capability lifecycle

```
DRAFT → ACTIVE → DEPRECATED → RETIRED
```

Capability has **no CANCELLED** status.

First version activation may auto-transition capability `DRAFT` → `ACTIVE` (no separate user action required).

| Capability status | UI lifecycle actions |
|-------------------|----------------------|
| `DRAFT` | Deprecate* only via manual capability PATCH if product allows; typically waits for version activation |
| `ACTIVE` | Deprecate |
| `DEPRECATED` | Retire |
| `RETIRED` | None |

*Capability-level transitions use PATCH `status`; version-driven activation is the normal path to `ACTIVE`.

### Capability version lifecycle

```
DRAFT
  ├── VALIDATED → ACTIVE → DEPRECATED → RETIRED
  └── CANCELLED

VALIDATED → CANCELLED
```

| Version status | Allowed UI actions |
|----------------|---------------------|
| `DRAFT` | Validate, Cancel |
| `VALIDATED` | Activate, Cancel |
| `ACTIVE` | Deprecate |
| `DEPRECATED` | Retire |
| `RETIRED` | None |
| `CANCELLED` | None |

### UI behavior notes

- **Activate** on a higher semver deprecates the current `ACTIVE` version automatically — confirm when replacing active version.
- **Activate** blocked when semver ≤ current `ACTIVE` — show `INVALID_LIFECYCLE_TRANSITION` messaging.
- **ACTIVE** version content is immutable — direct edit disabled; prompt to create new version.
- Status badges use `ETPBadge` with semantic colors + text label.
- Invalid transitions: HTTP **409** / `INVALID_LIFECYCLE_TRANSITION` — surface in toast or inline error.

---

## 26. Customer UI design boundary

| Layer | Design system |
|-------|----------------|
| ETP Core UI | **This document** — ETP branding and tokens |
| ETP Customer UI | Customer-specific design system allowed |
| Business Application UI | Customer design system |

Customer design systems may define logo, colors, fonts, branding, components, and UX conventions.

**Do not assume** ETP Core branding applies to customer-facing or business application UIs.

---

## 27. Cursor implementation rule

Future implementation **must**:

1. Read this document first.
2. Use centralized design tokens.
3. Use reusable `ETP*` components.
4. Follow approved visual references for brand and shell layout.
5. Preserve ETP architecture (capabilities ≠ nav items, aggregate model).
6. Avoid arbitrary colors, fonts, spacing, dimensions.
7. Avoid unnecessary navigation.
8. Avoid implementing undocumented functionality.

If a new visual requirement emerges:

1. Update this design system.
2. Review the design decision.
3. Implement.

**Do not bypass the design system.**

---

## 28. Implementation order

After this document is approved:

1. Design tokens
2. Theme / global styling
3. Reusable components
4. ETP logo / assets
5. Login
6. Core Shell
7. Routing
8. Capability Registry list
9. Capability detail (aggregate)
10. Backend integration (`/api/v1/capabilities`)
11. End-to-end validation

**Out of scope for initial Core UI implementation:**

- IAM integration UI
- Event/Kafka UI
- ETP Customer UI
- Business Application UIs

---

## 29. Foundations (summary)

Cross-cutting foundations referenced by components:

| Foundation | Notes |
|------------|-------|
| Color | §9 |
| Typography | §10 |
| Spacing | §11 |
| Radius | §12 |
| Elevation | §13 |
| Motion | §14 |
| Layout | §15 |
| Icons | §16 |

---

## 30. Screen guidelines (summary)

| Screen | Guideline |
|--------|-----------|
| **Login** | §21 — brand authority; no tenant/env on login |
| **Core shell** | §22 — header + sidebar + content; navy chrome |
| **List views** | Table-first; search + filter; minimal cards |
| **Detail views** | Page header + tabs; lifecycle actions in header or version row |
| **Forms** | Sectioned; validate at submit; API errors mapped |
| **Empty / loading / error** | Dedicated components; never blank screens |

---

## Document maintenance

When backend lifecycle or API behavior changes, update **§25** and [CAPABILITY_REGISTRY.md](CAPABILITY_REGISTRY.md) together.

When navigation scope changes, update **§23** and **§4** together.

**Version:** 1.0  
**Last updated:** 2026-08-18
