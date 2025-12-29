# Workflow Caching Strategy

**Last Updated**: December 29, 2025  
**Workflow Version**: 2.2.0

## Overview

The workflow implements a comprehensive caching strategy to minimize build times and reduce external dependencies.

---

## Cache Layers

### 1. JFC Configuration and Renaissance JAR Cache
**Location**: `prepare-jfr` job  
**Path**: `.cache/jfc.jfc`, `.cache/renaissance.jar`  
**Key**: `jfc-renaissance-${{ hashFiles('bin/releaser.py') }}`

**Purpose**: Cache the JFC configuration file and Renaissance benchmark JAR.

**Invalidation**: Cache invalidates when `bin/releaser.py` changes.

**Benefit**: Avoids rebuilding JFC config and re-downloading Renaissance JAR (~30s savings).

---

### 2. JFR Sample Files Cache (Per GC)
**Location**: `create-jfr` job (matrix)  
**Path**: `jfr/sample_${{ matrix.gc }}.jfr`  
**Key**: `jfr-${{ matrix.gc }}-${{ month }}-java${{ java-version }}`

**Purpose**: Cache JFR benchmark files for each garbage collector.

**Invalidation**: 
- Monthly rotation (YYYY-MM)
- Java version change
- Manual cache mode: `rebuild-jfr` or `rebuild-all`

**Benefit**: Avoids running Renaissance benchmarks (~5-6 min per GC).

**Note**: Only used when `cache-mode == 'normal'`

---

### 3. .cache Folder (JDK Sources & Build Artifacts)
**Location**: `build` job  
**Path**: `.cache/`  
**Key**: `jfr-cache-${{ month }}-java${{ java-version }}`  
**Restore Keys**:
1. `jfr-cache-${{ month }}-` (same month, any Java version)
2. `jfr-cache-` (any month, any Java version)

**Purpose**: Cache downloaded JDK sources and intermediate build artifacts.

**Contents**:
- JDK source code (downloaded from various repositories)
- Graal compiler sources
- JDK metadata XML files
- Any other intermediate files in `.cache/`

**Invalidation**:
- Monthly rotation (primary key)
- Java version change (primary key)
- Manual cache mode: `rebuild-all`

**Benefit**: Avoids re-downloading ~500MB+ of JDK sources (~10-15 min savings).

**Restore Strategy**:
- **Primary**: Exact match on month + Java version
- **Fallback 1**: Same month, different Java version (partial reuse)
- **Fallback 2**: Any cached version (minimal reuse)

---

### 4. Maven Dependencies Cache
**Location**: `build` job  
**Path**: `~/.m2/repository`  
**Key**: `maven-${{ runner.os }}-${{ hashFiles('pom.xml', 'pom_loader.xml') }}-v5`  
**Restore Keys**: `maven-${{ runner.os }}-`

**Purpose**: Cache Maven dependencies to avoid re-downloading.

**Invalidation**:
- When `pom.xml` or `pom_loader.xml` changes
- Version bump (v5)

**Benefit**: Avoids re-downloading Maven dependencies (~2-3 min savings).

**Note**: Local `me.bechberger` artifacts are cleaned before use to ensure fresh build.

---

### 5. Maven Dependencies Cache (Website)
**Location**: `deploy-website` job  
**Path**: `~/.m2/repository`  
**Key**: `maven-website-${{ runner.os }}-${{ hashFiles('website/pom.xml') }}-v5`  
**Restore Keys**: `maven-website-${{ runner.os }}-`

**Purpose**: Cache Maven dependencies for website build.

**Invalidation**: When `website/pom.xml` changes.

**Benefit**: Avoids re-downloading website dependencies (~1-2 min savings).

---

## Cache Modes

### normal (default)
- Uses all caches
- Fastest build time
- Recommended for daily development

**Behavior**:
- JFC/Renaissance: ✅ Cached
- JFR files: ✅ Cached
- .cache folder: ✅ Cached
- Maven deps: ✅ Cached

---

### rebuild-jfr
- Re-creates JFR benchmark files
- Keeps JDK sources and Maven dependencies
- Use when JFR profiles need updating

**Behavior**:
- JFC/Renaissance: ✅ Cached
- JFR files: ❌ Rebuilt
- .cache folder: ❌ Rebuilt (re-downloads JDK)
- Maven deps: ✅ Cached

---

### rebuild-all
- Fresh start, no caches
- Longest build time
- Use for debugging cache issues

**Behavior**:
- JFC/Renaissance: ❌ Rebuilt
- JFR files: ❌ Rebuilt
- .cache folder: ❌ Rebuilt
- Maven deps: ✅ Cached (not affected by mode)

---

## Cache Size Estimates

| Cache | Approximate Size | Compression Ratio |
|-------|------------------|-------------------|
| JFC + Renaissance | ~22 MB | ~10:1 |
| JFR (per GC) | ~500 KB - 2 MB | ~5:1 |
| .cache (JDK sources) | ~500 MB | ~3:1 |
| Maven dependencies (build) | ~200 MB | ~2:1 |
| Maven dependencies (website) | ~100 MB | ~2:1 |
| **Total** | **~1 GB** | **~3:1 avg** |

---

## Cache Hit Rates (Expected)

| Scenario | JFC | JFR | .cache | Maven | Total Time |
|----------|-----|-----|--------|-------|------------|
| **No changes** | 99% | 95% | 95% | 99% | ~12 min |
| **Code change** | 99% | 95% | 95% | 80% | ~13 min |
| **Dependency change** | 99% | 95% | 95% | 0% | ~15 min |
| **New month** | 99% | 0% | 50% | 99% | ~25 min |
| **Java upgrade** | 99% | 0% | 0% | 99% | ~35 min |
| **Cold start** | 0% | 0% | 0% | 0% | ~35 min |

---

## Optimization Tips

### 1. Cache Key Design
- **Good**: `jfr-cache-${{ month }}-java${{ version }}`
  - Specific enough to avoid stale data
  - General enough for good hit rate

- **Bad**: `jfr-cache-${{ github.sha }}`
  - Too specific, cache never hits
  - Wastes storage

### 2. Restore Keys
- Use hierarchical restore keys for graceful degradation
- Example: `jfr-cache-2025-12-java21` → `jfr-cache-2025-12-` → `jfr-cache-`

### 3. Cache Cleaning
- Clean local artifacts before use: `rm -rf ~/.m2/repository/me/bechberger/`
- Prevents stale local builds from interfering

### 4. Monthly Rotation
- Balances between:
  - Cache freshness (avoid stale data)
  - Cache reuse (avoid rebuilding too often)
- Chosen because JDK sources change monthly

---

## Monitoring

### Check Cache Usage
```bash
# Via GitHub UI
Actions → Workflow run → Caches tab

# Via CLI
gh cache list
```

### Cache Statistics
```bash
# View cache size
gh cache list --json sizeInBytes | jq '.[] | {key, size: .sizeInBytes}'

# Delete old caches
gh cache delete <cache-key>
```

---

## Troubleshooting

### Cache Not Restoring

**Problem**: Cache exists but not restoring.

**Solutions**:
1. Check cache key matches exactly
2. Verify restore-keys are in order (most specific first)
3. Check cache hasn't expired (7 days of no use)

### Cache Too Large

**Problem**: Cache approaching GitHub limit (10 GB per repo).

**Solutions**:
1. Review what's being cached in `.cache/`
2. Add `.cacheignore` patterns if needed
3. Consider shorter monthly rotation

### Stale Cache

**Problem**: Using outdated cached data.

**Solutions**:
1. Trigger workflow with `rebuild-all` mode
2. Manually delete cache via GitHub UI
3. Adjust cache key to force invalidation

---

## Future Improvements

1. **Add cache warming**: Pre-populate caches on schedule
2. **Cache analytics**: Track hit rates and sizes
3. **Smarter invalidation**: Use content hashes for JDK sources
4. **Distributed cache**: Share caches across branches
5. **Cache compression**: Optimize large files before caching

---

## Related Files

- `.github/workflows/build.yml` - Main workflow with cache configuration
- `bin/releaser.py` - Script that uses cached data
- `pom.xml`, `pom_loader.xml` - Maven dependencies
- `website/pom.xml` - Website dependencies

---

## Summary

The workflow uses **5 distinct cache layers** to optimize build performance:

1. ✅ JFC/Renaissance (~22 MB) - Avoids config rebuild
2. ✅ JFR files (~2-10 MB) - Avoids benchmark runs
3. ✅ .cache folder (~500 MB) - Avoids JDK downloads
4. ✅ Maven build deps (~200 MB) - Avoids dependency downloads
5. ✅ Maven website deps (~100 MB) - Avoids dependency downloads

**Result**: ~12 min builds (cache hit) vs ~35 min (cache miss) - **66% faster!**