import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Plus } from 'lucide-react';
import {
  ETPBadge,
  ETPButton,
  ETPEmptyState,
  ETPErrorState,
  ETPLoadingState,
  ETPPageHeader,
  ETPSearch,
  ETPSelect,
} from '@/components/ui';
import { iconSizes, iconStroke } from '@/lib/iconSizes';
import { WorklistMenu } from './components/WorklistMenu';
import {
  capabilityStatusVariant,
  filterCapabilities,
  formatCapabilityStatusLabel,
  formatCapabilityUpdatedAt,
  shortCapabilityId,
  uniqueSortedValues,
} from './capabilityPresentation';
import { useCapabilitiesList } from './useCapabilitiesList';
import type { CapabilitySummary } from './types';
import styles from './CapabilitiesListPage.module.css';

const PAGE_SIZE_OPTIONS = [10, 25, 50, 100];
const DEFAULT_PAGE_SIZE = 25;

function createRowActionGroups(onView: () => void) {
  return [
    {
      items: [
        { id: 'view', label: 'View', onSelect: onView },
        { id: 'edit', label: 'Edit', disabled: true },
        { id: 'clone', label: 'Clone', disabled: true },
      ],
    },
    {
      items: [
        { id: 'submit-review', label: 'Submit for Review', disabled: true },
        { id: 'publish', label: 'Publish', disabled: true },
        { id: 'new-version', label: 'Create New Version', disabled: true },
      ],
    },
    {
      items: [
        { id: 'deactivate', label: 'Deactivate', disabled: true },
        { id: 'delete', label: 'Delete', disabled: true, destructive: true },
      ],
    },
  ];
}

export function CapabilitiesListPage() {
  const navigate = useNavigate();
  const { capabilities, loading, error, reload } = useCapabilitiesList();
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('');
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  const statusOptions = useMemo(
    () => uniqueSortedValues(capabilities.map((c) => c.status)),
    [capabilities],
  );

  const categoryOptions = useMemo(
    () => uniqueSortedValues(capabilities.map((c) => c.category)),
    [capabilities],
  );

  const filtered = useMemo(
    () =>
      filterCapabilities(capabilities, {
        search,
        status: statusFilter,
        category: categoryFilter,
      }),
    [capabilities, search, statusFilter, categoryFilter],
  );

  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
  const safePage = Math.min(page, totalPages - 1);

  const paginated = useMemo(() => {
    const start = safePage * pageSize;
    return filtered.slice(start, start + pageSize);
  }, [filtered, safePage, pageSize]);

  useEffect(() => {
    setPage(0);
  }, [search, statusFilter, categoryFilter, pageSize]);

  useEffect(() => {
    if (page !== safePage) {
      setPage(safePage);
    }
  }, [page, safePage]);

  const hasActiveFilters = Boolean(search.trim() || statusFilter || categoryFilter);
  const pageIds = paginated.map((c) => c.id);
  const allOnPageSelected =
    pageIds.length > 0 && pageIds.every((id) => selectedIds.has(id));
  const someOnPageSelected = pageIds.some((id) => selectedIds.has(id));

  const clearFilters = () => {
    setSearch('');
    setStatusFilter('');
    setCategoryFilter('');
  };

  const toggleRow = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const toggleAllOnPage = () => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (allOnPageSelected) {
        pageIds.forEach((id) => next.delete(id));
      } else {
        pageIds.forEach((id) => next.add(id));
      }
      return next;
    });
  };

  const handleRefresh = () => {
    setSelectedIds(new Set());
    reload();
  };

  const utilitiesGroups = [
    {
      items: [
        { id: 'export', label: 'Export', disabled: true },
        { id: 'import', label: 'Import', disabled: true },
        { id: 'refresh', label: 'Refresh', onSelect: handleRefresh },
      ],
    },
    {
      items: [
        { id: 'bulk-update', label: 'Bulk Update', disabled: true },
        { id: 'manage-columns', label: 'Manage Columns', disabled: true },
        { id: 'saved-views', label: 'Saved Views', disabled: true },
      ],
    },
    {
      items: [
        { id: 'api-docs', label: 'API Documentation', disabled: true },
        { id: 'download-template', label: 'Download Template', disabled: true },
      ],
    },
  ];

  const pageStart = filtered.length === 0 ? 0 : safePage * pageSize + 1;
  const pageEnd = Math.min(filtered.length, (safePage + 1) * pageSize);

  return (
    <div className={styles.page}>
      <ETPPageHeader
        title="Capabilities"
        description="ETP capability registry and lifecycle management."
        actions={
          <div className={styles.headerActions}>
            <ETPButton
              variant="primary"
              size="sm"
              disabled
              title="Capability registration will be available in a later phase."
            >
              <Plus size={iconSizes.sm} strokeWidth={iconStroke} aria-hidden />
              Create Capability
            </ETPButton>
            <WorklistMenu label="Utilities" groups={utilitiesGroups} />
          </div>
        }
      />

      <div className={styles.toolbar}>
        <ETPSearch
          className={styles.search}
          placeholder="Search by name, code, description…"
          aria-label="Search capabilities"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <ETPSelect
          className={styles.filterSelect}
          aria-label="Filter by status"
          value={statusFilter}
          onChange={(event) => setStatusFilter(event.target.value)}
          placeholder="Status"
          options={[
            { value: '', label: 'All statuses' },
            ...statusOptions.map((status) => ({
              value: status,
              label: formatCapabilityStatusLabel(status),
            })),
          ]}
        />
        <ETPSelect
          className={styles.filterSelect}
          aria-label="Filter by category"
          value={categoryFilter}
          onChange={(event) => setCategoryFilter(event.target.value)}
          placeholder="Category"
          options={[
            { value: '', label: 'All categories' },
            ...categoryOptions.map((category) => ({ value: category, label: category })),
          ]}
        />
        <ETPButton variant="secondary" size="sm" disabled title="More filters coming in a later phase.">
          More Filters
        </ETPButton>
      </div>

      {selectedIds.size > 0 && (
        <div className={styles.bulkBar} role="status">
          <span className={styles.bulkCount}>{selectedIds.size} selected</span>
          <WorklistMenu
            label="Bulk Actions"
            groups={[
              {
                items: [
                  { id: 'bulk-owner', label: 'Change Owner', disabled: true },
                  { id: 'bulk-category', label: 'Change Category', disabled: true },
                  { id: 'bulk-status', label: 'Change Status', disabled: true },
                  { id: 'bulk-publish', label: 'Publish', disabled: true },
                  { id: 'bulk-deprecate', label: 'Deprecate', disabled: true },
                  { id: 'bulk-export', label: 'Export', disabled: true },
                  { id: 'bulk-delete', label: 'Delete', disabled: true, destructive: true },
                ],
              },
            ]}
            align="left"
          />
        </div>
      )}

      <div className={styles.listHeader}>
        <h2 className={styles.listTitle}>Capability List</h2>
        <span className={styles.listCount}>
          {loading ? '…' : `${filtered.length} capabilities`}
        </span>
      </div>

      <div className={styles.tableCard}>
        {loading && (
          <ETPLoadingState message="Loading capabilities…" className={styles.state} />
        )}

        {!loading && error && (
          <ETPErrorState
            title={error}
            className={styles.state}
            action={
              <ETPButton variant="secondary" size="sm" onClick={handleRefresh}>
                Retry
              </ETPButton>
            }
          />
        )}

        {!loading && !error && filtered.length === 0 && (
          <ETPEmptyState
            title="No capabilities found"
            description={
              hasActiveFilters
                ? 'No capabilities match your current search or filters.'
                : 'No capabilities are registered yet.'
            }
            className={styles.state}
            action={
              hasActiveFilters ? (
                <ETPButton variant="secondary" size="sm" onClick={clearFilters}>
                  Clear Filters
                </ETPButton>
              ) : undefined
            }
          />
        )}

        {!loading && !error && filtered.length > 0 && (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th className={styles.checkboxCol}>
                    <input
                      type="checkbox"
                      className={styles.rowCheckbox}
                      aria-label="Select all on page"
                      checked={allOnPageSelected}
                      ref={(input) => {
                        if (input) {
                          input.indeterminate = someOnPageSelected && !allOnPageSelected;
                        }
                      }}
                      onChange={toggleAllOnPage}
                    />
                  </th>
                  <th>ID</th>
                  <th>Name</th>
                  <th>Code</th>
                  <th>Category</th>
                  <th>Status</th>
                  <th>Version</th>
                  <th>Updated</th>
                  <th className={styles.actionsCol} aria-label="Actions">
                    <span className={styles.srOnly}>Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {paginated.map((capability) => (
                  <CapabilityRow
                    key={capability.id}
                    capability={capability}
                    selected={selectedIds.has(capability.id)}
                    onToggle={() => toggleRow(capability.id)}
                    onView={() => navigate(`/capabilities/${capability.id}`)}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {!loading && !error && filtered.length > 0 && (
        <div className={styles.pagination}>
          <span className={styles.pageSummary}>
            Showing {pageStart}–{pageEnd} of {filtered.length}
          </span>
          <div className={styles.pageControls}>
            <ETPSelect
              className={styles.pageSizeSelect}
              aria-label="Rows per page"
              value={String(pageSize)}
              onChange={(event) => setPageSize(Number(event.target.value))}
              options={PAGE_SIZE_OPTIONS.map((size) => ({
                value: String(size),
                label: `${size} rows`,
              }))}
            />
            <ETPButton
              variant="secondary"
              size="sm"
              disabled={safePage <= 0}
              onClick={() => setPage((p) => p - 1)}
            >
              Previous
            </ETPButton>
            <span className={styles.pageNumber}>
              Page {safePage + 1} of {totalPages}
            </span>
            <ETPButton
              variant="secondary"
              size="sm"
              disabled={safePage >= totalPages - 1}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </ETPButton>
          </div>
        </div>
      )}
    </div>
  );
}

interface CapabilityRowProps {
  capability: CapabilitySummary;
  selected: boolean;
  onToggle: () => void;
  onView: () => void;
}

function CapabilityRow({ capability, selected, onToggle, onView }: CapabilityRowProps) {
  const rowActionGroups = createRowActionGroups(onView);

  return (
    <tr className={styles.row}>
      <td className={styles.checkboxCol}>
        <input
          type="checkbox"
          className={styles.rowCheckbox}
          aria-label={`Select ${capability.name}`}
          checked={selected}
          onChange={onToggle}
        />
      </td>
      <td className={styles.idCell} title={capability.id}>
        <code className={styles.mono}>{shortCapabilityId(capability.id)}</code>
      </td>
      <td className={styles.nameCell}>
        <Link to={`/capabilities/${capability.id}`} className={styles.nameLink}>
          {capability.name}
        </Link>
      </td>
      <td className={styles.codeCell}>
        <code className={styles.mono}>{capability.code}</code>
      </td>
      <td>{capability.category ?? '—'}</td>
      <td>
        <ETPBadge variant={capabilityStatusVariant(capability.status)}>
          {formatCapabilityStatusLabel(capability.status)}
        </ETPBadge>
      </td>
      <td>{capability.version ?? '—'}</td>
      <td className={styles.updatedCell}>{formatCapabilityUpdatedAt(capability.updatedAt)}</td>
      <td className={styles.actionsCol}>
        <WorklistMenu
          label={`Actions for ${capability.name}`}
          iconOnly
          align="right"
          groups={rowActionGroups}
        />
      </td>
    </tr>
  );
}
