# @exeris/codegen-ts

> Exeris Frontend Code Generator for Angular 21+

Generates TypeScript interfaces, Angular services, form components, and list components from Exeris domain metadata.

## Features

- 🎯 **Angular 21+ Support** - Standalone components, Signals, Control Flow, Resource API
- 📝 **TypeScript Types** - Interfaces and Zod schemas from Java domain models
- 🔧 **Services** - HttpClient-based services with full CRUD support
- 📋 **Form Components** - Reactive forms with validation, integrated with Signals
- 📊 **List Components** - Data tables with pagination, sorting, filtering
- 🎨 **Tailwind CSS** - Modern utility-first styling out of the box
- ✅ **Zod Validation** - Runtime validation schemas for type safety
- 🔒 **Security First** - Minimal dependencies (picocolors instead of chalk) to reduce supply chain risk

## Installation

```bash
npm install -g @exeris/codegen-ts
# or
npm install --save-dev @exeris/codegen-ts
```

## Quick Start

### 1. Generate domain metadata (Java)

Run Maven compile to generate metadata from `@ExerisDomain` annotated classes:

```bash
mvn clean compile
```

This creates JSON metadata files in `target/classes/exeris-metadata/`.

### 2. Run code generator

```bash
exeris-gen generate --input target/classes/exeris-metadata --output src/app/generated
```

### 3. Use generated code

```typescript
import { ProductService } from './generated/services/product.service';
import { ProductFormComponent } from './generated/components/product-form.component';
import { ProductListComponent } from './generated/components/product-list.component';
```

## CLI Reference

### `exeris-gen generate`

Generate frontend code from domain metadata.

```bash
exeris-gen generate [options]

Options:
  -i, --input <path>     Input path for metadata JSON files (default: "target/classes/exeris-metadata")
  -o, --output <path>    Output directory for generated code (default: "src/app/generated")
  --api-base <path>      API base path (default: "/api")
  --framework <name>     Target framework: angular, react, vue (default: "angular")
  --styling <name>       Style system: tailwind, material, bootstrap, none (default: "tailwind")
  --no-zod               Skip Zod schema generation
  --no-services          Skip service generation
  --no-forms             Skip form component generation
  --no-lists             Skip list component generation
  --overwrite            Overwrite existing files
  --dry-run              Show what would be generated without writing files
  -v, --verbose          Verbose output
```

### `exeris-gen init`

Create a configuration file.

```bash
exeris-gen init [options]

Options:
  -f, --force    Overwrite existing config file
```

## Configuration File

Create `exeris-codegen.json` in your project root:

```json
{
  "inputPath": "target/classes/exeris-metadata",
  "outputPath": "src/app/generated",
  "framework": "angular",
  "styling": "tailwind",
  "standalone": true,
  "signals": true,
  "lazyRoutes": true,
  "generateZod": true,
  "generateServices": true,
  "generateForms": true,
  "generateLists": true,
  "apiBasePath": "/api"
}
```

## Generated Structure

```
src/app/generated/
├── types/                    # TypeScript interfaces
│   ├── product.types.ts
│   └── customer.types.ts
├── schemas/                  # Zod validation schemas
│   ├── product.schema.ts
│   └── customer.schema.ts
├── services/                 # Angular services
│   ├── product.service.ts
│   └── customer.service.ts
└── components/               # Angular components
    ├── product-form.component.ts
    ├── product-list.component.ts
    ├── customer-form.component.ts
    └── customer-list.component.ts
```

## Type Mapping

| Java Type | TypeScript Type | Form Control |
|-----------|-----------------|--------------|
| `String` | `string` | `<input type="text">` |
| `Integer`, `Long` | `number` | `<input type="number">` |
| `Boolean` | `boolean` | `<input type="checkbox">` |
| `BigDecimal` | `string` | `<input type="text">` |
| `LocalDate` | `string` | `<input type="date">` |
| `LocalDateTime`, `Instant` | `string` | `<input type="datetime-local">` |
| `UUID` | `string` | `<input type="text">` |
| `List<T>` | `T[]` | `<textarea>` |
| `enum` | `string` | `<select>` |

## Integration with Exeris

This generator is part of the Exeris tooling ecosystem:

1. **exeris-processor** - Annotation processor that generates JSON metadata at compile time
2. **exeris-codegen-java** - Java/Spring code generator for backend
3. **exeris-codegen-ts** - TypeScript/Angular code generator for frontend

### Full Stack Generation Script

```powershell
# Windows
.\scripts\generate-all.ps1

# Unix/Linux/macOS
./scripts/generate-all.sh
```

## Development

```bash
# Install dependencies
npm install

# Build
npm run build

# Run in development mode
npm run dev generate --input path/to/metadata

# Run tests
npm test
```

## License

Apache-2.0

