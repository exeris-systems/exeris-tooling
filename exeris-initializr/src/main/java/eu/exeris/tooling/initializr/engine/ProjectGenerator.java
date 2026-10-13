package eu.exeris.tooling.initializr.engine;

import eu.exeris.tooling.initializr.model.DatabaseType;
import eu.exeris.tooling.initializr.model.ExerisCapability;
import eu.exeris.tooling.initializr.model.ProjectScaffoldRequest;
import eu.exeris.tooling.initializr.model.ProjectTopology;
import eu.exeris.tooling.initializr.model.ProjectTree;
import eu.exeris.tooling.initializr.model.ScaffoldFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Orchestrates file scaffolding for Exeris projects across E2E, Backend-only, and Frontend-only topologies.
 */
public final class ProjectGenerator {

    private ProjectGenerator() {}

    /**
     * Generates an in-memory ProjectTree for the specified request.
     */
    public static ProjectTree generate(ProjectScaffoldRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        List<ScaffoldFile> files = new ArrayList<>();
        ProjectTopology topology = request.topology();

        switch (topology) {
            case E2E -> {
                generateRootFiles(request, files);
                generateBackendFiles(request, "backend/", files);
                generateFrontendFiles(request, "frontend/", files);
            }
            case BACKEND_ONLY -> {
                generateRootGitignore(files, "");
                generateRootReadme(request, files, "");
                generateBackendFiles(request, "", files);
            }
            case FRONTEND_ONLY -> {
                generateRootGitignore(files, "");
                generateFrontendFiles(request, "", files);
            }
        }

        return ProjectTree.of(files);
    }

    // ==================== ROOT ORCHESTRATION FILES ====================

    private static void generateRootFiles(ProjectScaffoldRequest req, List<ScaffoldFile> files) {
        generateRootGitignore(files, "");
        generateRootReadme(req, files, "");

        if (req.database() == DatabaseType.POSTGRESQL) {
            files.add(ScaffoldFile.text("docker-compose.yml", """
                    services:
                      postgres:
                        image: postgres:16-alpine
                        container_name: %s-postgres
                        environment:
                          POSTGRES_DB: appdb
                          POSTGRES_USER: postgres
                          POSTGRES_PASSWORD: postgres
                        ports:
                          - "5432:5432"
                        volumes:
                          - pgdata:/var/lib/postgresql/data
                    volumes:
                      pgdata:
                    """.formatted(req.slug())));
        }
    }

    private static void generateRootGitignore(List<ScaffoldFile> files, String prefix) {
        files.add(ScaffoldFile.text(prefix + ".gitignore", """
                # Maven & Java
                target/
                *.class
                .mvn/wrapper/maven-wrapper.jar
                
                # Node & Frontend
                node_modules/
                dist/
                .angular/
                *.log
                
                # IDEs & Editor state
                .idea/
                *.iml
                .vscode/
                .DS_Store
                *.swp
                """));
    }

    private static void generateRootReadme(ProjectScaffoldRequest req, List<ScaffoldFile> files, String prefix) {
        String entityName = req.entityName();

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(req.name()).append("\n\n");
        sb.append("> ").append(req.description()).append("\n\n");
        sb.append("**Topology:** `").append(req.topology())
                .append("` | **Database:** `").append(req.database()).append("`\n\n");

        if (req.topology() == ProjectTopology.E2E) {
            sb.append("## Structure\n\n");
            sb.append("This repository is an Exeris Full-Stack Monorepo:\n");
            sb.append("- [`/backend`](backend/): Java 25 + Exeris Kernel SDK, domain models, annotations.\n");
            sb.append("- [`/frontend`](frontend/): Angular 22 standalone app with Tailwind CSS v4 and codegen-ts.\n\n");
            sb.append("## Quick Start\n\n");
            sb.append("### 1. Compile Backend & Generate Metadata\n\n");
            sb.append("```bash\ncd backend\nmvn clean compile\n```\n\n");
            sb.append("This compiles your `@ExerisDomain` annotated classes (such as `")
                    .append(entityName).append("`) and produces `target/classes/exeris-metadata/`.\n\n");
            sb.append("### 2. Generate Frontend Components & Launch Dev Server\n\n");
            sb.append("```bash\ncd ../frontend\npnpm install\npnpm run codegen\npnpm start\n```\n\n");
            sb.append("The frontend starts at `http://localhost:4200` with proxies routed to `http://localhost:8080`.\n\n");
        } else if (req.topology() == ProjectTopology.BACKEND_ONLY) {
            sb.append("## Quick Start\n\n");
            sb.append("```bash\nmvn clean compile\nmvn test\n```\n\n");
        } else {
            sb.append("## Quick Start\n\n");
            sb.append("```bash\npnpm install\npnpm start\n```\n\n");
        }

        sb.append("## Included Capabilities\n\n");
        if (req.capabilities().isEmpty()) {
            sb.append("- Minimal Core Starter\n");
        } else {
            for (ExerisCapability cap : req.capabilities()) {
                sb.append("- **").append(cap.category()).append("**: `")
                        .append(cap.key()).append("` - ").append(cap.description()).append("\n");
            }
        }

        files.add(ScaffoldFile.text(prefix + "README.md", sb.toString()));
    }

    // ==================== BACKEND SCAFFOLDING ====================

    private static void generateBackendFiles(ProjectScaffoldRequest req, String prefix, List<ScaffoldFile> files) {
        String packagePath = req.packageName().replace('.', '/');

        // 1. pom.xml
        files.add(ScaffoldFile.text(prefix + "pom.xml", generateBackendPom(req)));

        // 2. application.properties
        files.add(ScaffoldFile.text(prefix + "src/main/resources/application.properties", """
                # Exeris Kernel Application Configuration
                exeris.application.name=%s
                exeris.datasource.url=%s
                exeris.datasource.driver-class-name=%s
                exeris.http.port=8080
                """.formatted(req.slug(), req.database().defaultJdbcUrl(), req.database().driverClassName())));

        // 3. Domain Entity
        if (req.includeDemoEntity()) {
            files.add(ScaffoldFile.text(
                    prefix + "src/main/java/" + packagePath + "/domain/" + req.entityName() + ".java",
                    generateDomainEntity(req)
            ));

            // In E2E topology: Presentation IR (@View)
            if (req.topology() == ProjectTopology.E2E) {
                files.add(ScaffoldFile.text(
                        prefix + "src/main/java/" + packagePath + "/view/" + req.entityName() + "DashboardView.java",
                        generateViewClass(req)
                ));
            }
        }

        // 4. Capability Module
        files.add(ScaffoldFile.text(
                prefix + "src/main/java/" + packagePath + "/caps/ApplicationModule.java",
                generateCapabilityModule(req)
        ));

        // 5. Test
        if (req.includeDemoEntity()) {
            files.add(ScaffoldFile.text(
                    prefix + "src/test/java/" + packagePath + "/" + req.entityName() + "E2ETest.java",
                    generateE2ETest(req)
            ));
        }
    }

    private static String generateBackendPom(ProjectScaffoldRequest req) {
        StringBuilder capsXml = new StringBuilder();
        for (ExerisCapability cap : req.capabilities()) {
            capsXml.append("""
                    <dependency>
                        <groupId>%s</groupId>
                        <artifactId>%s</artifactId>
                    </dependency>
            """.formatted(cap.groupId(), cap.artifactId()));
        }

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>eu.exeris</groupId>
                        <artifactId>exeris-app-parent</artifactId>
                        <version>0.10.0-SNAPSHOT</version>
                        <relativePath/>
                    </parent>

                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    <description>%s</description>

                    <properties>
                        <exeris.tests>true</exeris.tests>
                    </properties>

                    <dependencies>
                        <!-- Exeris Application Starter: SDK Annotations, Kernel Core, Community Driver, Jackson -->
                        <dependency>
                            <groupId>eu.exeris</groupId>
                            <artifactId>exeris-app-starter</artifactId>
                            <type>pom</type>
                        </dependency>

                        <!-- Selected Database Driver -->
                        <dependency>
                            <groupId>%s</groupId>
                            <artifactId>%s</artifactId>
                            <version>%s</version>
                            <scope>runtime</scope>
                        </dependency>

                        <!-- Selected Capabilities -->
                %s
                        <!-- Testing Support -->
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.11.4</version>
                            <scope>test</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.assertj</groupId>
                            <artifactId>assertj-core</artifactId>
                            <version>3.27.7</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(
                req.groupId(),
                req.artifactId(),
                req.version(),
                req.description(),
                req.database().groupId(),
                req.database().artifactId(),
                req.database().defaultVersion(),
                capsXml.toString()
        );
    }

    private static String generateDomainEntity(ProjectScaffoldRequest req) {
        String entityName = req.entityName();
        String lowerName = entityName.toLowerCase(Locale.ROOT);

        return """
                package %s.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.UI;
                import eu.exeris.sdk.annotation.Validation;

                import java.util.List;
                import java.util.UUID;

                /**
                 * Demonstrative Exeris domain entity with complete backend-to-frontend UI & validation annotations.
                 */
                @ExerisDomain(module = "%s", path = "%s")
                @UI(
                    listView = true,
                    detailView = true,
                    createForm = true,
                    editForm = true,
                    searchable = true,
                    filterable = true,
                    exportable = true
                )
                public class %s {

                    private UUID id;

                    @Field(label = "Title", required = true, searchable = true, filterable = true)
                    @Validation(minLength = 3, maxLength = 100)
                    @UI(componentType = UI.ComponentType.TEXT_INPUT, displayInList = true)
                    private String title;

                    @Field(label = "Description")
                    @UI(componentType = UI.ComponentType.TEXT_AREA, displayInDetail = true)
                    private String description;

                    @Field(label = "Status", required = true, filterable = true)
                    @UI(componentType = UI.ComponentType.SELECT, displayInList = true)
                    private String status;

                    @Field(label = "Priority", filterable = true)
                    @Validation(min = 1, max = 5, message = "Priority must be between 1 and 5")
                    @UI(componentType = UI.ComponentType.NUMBER_INPUT, displayInList = true)
                    private Integer priority;

                    @Field(label = "Tags")
                    private List<String> tags;

                    public UUID getId() {
                        return id;
                    }

                    public void setId(UUID id) {
                        this.id = id;
                    }

                    public String getTitle() {
                        return title;
                    }

                    public void setTitle(String title) {
                        this.title = title;
                    }

                    public String getDescription() {
                        return description;
                    }

                    public void setDescription(String description) {
                        this.description = description;
                    }

                    public String getStatus() {
                        return status;
                    }

                    public void setStatus(String status) {
                        this.status = status;
                    }

                    public Integer getPriority() {
                        return priority;
                    }

                    public void setPriority(Integer priority) {
                        this.priority = priority;
                    }

                    public List<String> getTags() {
                        return tags;
                    }

                    public void setTags(List<String> tags) {
                        this.tags = tags;
                    }
                }
                """.formatted(
                req.packageName(),
                req.entityModule(),
                req.entityPath(),
                entityName,
                lowerName
        );
    }

    private static String generateViewClass(ProjectScaffoldRequest req) {
        String entityName = req.entityName();

        return """
                package %s.view;

                import eu.exeris.sdk.annotation.Bind;
                import eu.exeris.sdk.annotation.Block;
                import eu.exeris.sdk.annotation.Region;
                import eu.exeris.sdk.annotation.View;

                /**
                 * Backend-anchored Presentation IR (RFC-2026-06-28) consumed by @exeris/codegen-ts
                 * to generate standalone Angular 22 page components with signal stores and paired routes.
                 */
                @View(
                    name = "%sDashboard",
                    kind = View.Kind.PAGE,
                    route = "%s",
                    title = "%s Management"
                )
                public final class %sDashboardView {

                    @Region(slot = "header")
                    HeaderRegion header;

                    @Region(slot = "body")
                    BodyRegion body;

                    static final class HeaderRegion {
                        @Block(type = Block.BlockType.HERO)
                        @Bind(source = Bind.Source.STATIC)
                        String banner;
                    }

                    static final class BodyRegion {
                        @Block(type = Block.BlockType.GRID)
                        @Bind(source = Bind.Source.ENTITY)
                        String content;
                    }
                }
                """.formatted(
                req.packageName(),
                entityName,
                req.entityPath(),
                entityName,
                entityName
        );
    }

    private static String generateCapabilityModule(ProjectScaffoldRequest req) {
        return """
                package %s.caps;

                import eu.exeris.sdk.annotation.capability.CapabilityModule;

                /**
                 * Exeris composition host module registering capabilities.
                 */
                @CapabilityModule
                public class ApplicationModule {
                }
                """.formatted(req.packageName());
    }

    private static String generateE2ETest(ProjectScaffoldRequest req) {
        String entityName = req.entityName();

        return """
                package %s;

                import %s.domain.%s;
                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;

                import java.util.List;
                import java.util.UUID;

                import static org.assertj.core.api.Assertions.assertThat;

                class %sE2ETest {

                    @Test
                    @DisplayName("Entity instantiates and retains properties properly")
                    void entityBasics() {
                        %s entity = new %s();
                        UUID id = UUID.randomUUID();
                        entity.setId(id);
                        entity.setTitle("Demonstration %s");
                        entity.setStatus("ACTIVE");
                        entity.setPriority(1);
                        entity.setTags(List.of("starter", "exeris"));

                        assertThat(entity.getId()).isEqualTo(id);
                        assertThat(entity.getTitle()).isEqualTo("Demonstration %s");
                        assertThat(entity.getStatus()).isEqualTo("ACTIVE");
                        assertThat(entity.getPriority()).isEqualTo(1);
                        assertThat(entity.getTags()).containsExactly("starter", "exeris");
                    }
                }
                """.formatted(
                req.packageName(),
                req.packageName(),
                entityName,
                entityName,
                entityName,
                entityName,
                entityName,
                entityName
        );
    }

    // ==================== FRONTEND SCAFFOLDING ====================

    private static void generateFrontendFiles(ProjectScaffoldRequest req, String prefix, List<ScaffoldFile> files) {
        String entityName = req.entityName();
        String lowerName = entityName.toLowerCase(Locale.ROOT);

        // 1. package.json
        files.add(ScaffoldFile.text(prefix + "package.json", """
                {
                  "name": "%s-frontend",
                  "version": "0.1.0",
                  "private": true,
                  "type": "module",
                  "scripts": {
                    "start": "vite",
                    "build": "vite build",
                    "codegen": "exeris-gen generate --input ../backend/target/classes/exeris-metadata"
                  },
                  "dependencies": {
                    "@angular/core": "^22.0.0",
                    "@angular/common": "^22.0.0",
                    "@angular/forms": "^22.0.0",
                    "@angular/router": "^22.0.0",
                    "rxjs": "^7.8.1",
                    "zod": "^3.24.2"
                  },
                  "devDependencies": {
                    "@exeris/codegen-ts": "^0.10.0",
                    "tailwindcss": "^4.0.0",
                    "typescript": "^5.7.3",
                    "vite": "^6.2.0"
                  }
                }
                """.formatted(req.slug())));

        // 2. proxy.conf.json
        files.add(ScaffoldFile.text(prefix + "proxy.conf.json", """
                {
                  "/api": {
                    "target": "http://localhost:8080",
                    "secure": false
                  },
                  "%s": {
                    "target": "http://localhost:8080",
                    "secure": false
                  }
                }
                """.formatted(req.entityPath())));

        // 3. tsconfig.json
        files.add(ScaffoldFile.text(prefix + "tsconfig.json", """
                {
                  "compilerOptions": {
                    "target": "ES2022",
                    "module": "ESNext",
                    "moduleResolution": "bundler",
                    "strict": true,
                    "esModuleInterop": true,
                    "skipLibCheck": true,
                    "forceConsistentCasingInFileNames": true,
                    "experimentalDecorators": true,
                    "emitDecoratorMetadata": false,
                    "useDefineForClassFields": false
                  },
                  "include": ["src/**/*.ts"]
                }
                """));

        // 4. index.html
        files.add(ScaffoldFile.text(prefix + "index.html", """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>%s · Exeris Studio</title>
                  <link rel="stylesheet" href="/src/styles.css">
                </head>
                <body class="bg-slate-950 text-slate-100 min-h-screen">
                  <app-root></app-root>
                  <script type="module" src="/src/main.ts"></script>
                </body>
                </html>
                """.formatted(req.name())));

        // 5. styles.css
        files.add(ScaffoldFile.text(prefix + "src/styles.css", """
                @import "tailwindcss";

                :root {
                  --ex-bg: #090d16;
                  --ex-surface: #101626;
                  --ex-accent: #00e5ff;
                  --ex-line: #1e293b;
                }
                """));

        // 6. main.ts
        files.add(ScaffoldFile.text(prefix + "src/main.ts", """
                import { bootstrapApplication } from '@angular/platform-browser';
                import { provideRouter } from '@angular/router';
                import { provideHttpClient } from '@angular/common/http';
                import { AppComponent } from './app/app.component';
                import { routes } from './app/app.routes';

                bootstrapApplication(AppComponent, {
                  providers: [
                    provideRouter(routes),
                    provideHttpClient()
                  ]
                }).catch(err => console.error(err));
                """));

        // 7. app.routes.ts
        files.add(ScaffoldFile.text(prefix + "src/app/app.routes.ts", """
                import { Routes } from '@angular/router';
                import { %sListComponent } from './components/%s-list.component';

                export const routes: Routes = [
                  { path: '', redirectTo: '%s', pathMatch: 'full' },
                  { path: '%s', component: %sListComponent },
                ];
                """.formatted(
                entityName,
                lowerName,
                lowerName,
                lowerName,
                entityName
        )));

        // 8. app.component.ts
        files.add(ScaffoldFile.text(prefix + "src/app/app.component.ts", """
                import { Component, ChangeDetectionStrategy } from '@angular/core';
                import { RouterModule } from '@angular/router';

                @Component({
                  selector: 'app-root',
                  standalone: true,
                  imports: [RouterModule],
                  template: `
                    <div class="min-h-screen flex flex-col font-sans">
                      <header class="border-b border-slate-800 bg-slate-900/60 px-6 py-4 flex justify-between">
                        <div class="flex items-center gap-3">
                          <span class="px-2 py-0.5 text-xs font-mono bg-cyan-500/20 text-cyan-400 rounded">EXERIS</span>
                          <h1 class="text-lg font-semibold tracking-tight text-white">%s</h1>
                        </div>
                        <nav class="flex items-center gap-4 text-xs font-mono text-slate-400">
                          <span class="text-emerald-400">● RUNNING</span>
                          <span>ANGULAR 22</span>
                          <span>TAILWIND v4</span>
                        </nav>
                      </header>
                      <main class="flex-1 p-8 max-w-7xl mx-auto w-full">
                        <router-outlet></router-outlet>
                      </main>
                    </div>
                  `,
                  changeDetection: ChangeDetectionStrategy.OnPush,
                })
                export class AppComponent {}
                """.formatted(req.name())));

        // 9. Component: List
        files.add(ScaffoldFile.text(prefix + "src/app/components/" + lowerName + "-list.component.ts", """
                import { Component, signal, ChangeDetectionStrategy } from '@angular/core';
                import { CommonModule } from '@angular/common';

                export interface %sItem {
                  id: string;
                  title: string;
                  status: string;
                  priority: number;
                }

                @Component({
                  selector: 'app-%s-list',
                  standalone: true,
                  imports: [CommonModule],
                  template: `
                    <div class="flex flex-col gap-6">
                      <div class="flex items-center justify-between">
                        <div>
                          <div class="text-xs font-mono uppercase text-cyan-400 tracking-wider">DOMAIN SCAFFOLD</div>
                          <h2 class="text-2xl font-bold text-white mt-1">%s Management</h2>
                        </div>
                        <button class="px-3 py-1.5 bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-mono text-xs">
                          + NEW %s
                        </button>
                      </div>

                      <div class="border border-slate-800 bg-slate-900/50 rounded overflow-hidden">
                        <table class="w-full text-left text-xs">
                          <thead class="bg-slate-900 border-b border-slate-800 text-slate-400 font-mono uppercase">
                            <tr>
                              <th class="py-3 px-4">Title</th>
                              <th class="py-3 px-4">Status</th>
                              <th class="py-3 px-4">Priority</th>
                              <th class="py-3 px-4 text-right">Actions</th>
                            </tr>
                          </thead>
                          <tbody class="divide-y divide-slate-800 font-mono">
                            @for (item of items(); track item.id) {
                              <tr class="hover:bg-slate-800/40">
                                <td class="py-3 px-4 text-white font-semibold">{{ item.title }}</td>
                                <td class="py-3 px-4"><span class="text-emerald-400">{{ item.status }}</span></td>
                                <td class="py-3 px-4 text-slate-300">P{{ item.priority }}</td>
                                <td class="py-3 px-4 text-right">
                                  <button class="text-cyan-400 hover:underline">Edit</button>
                                </td>
                              </tr>
                            }
                          </tbody>
                        </table>
                      </div>
                    </div>
                  `,
                  changeDetection: ChangeDetectionStrategy.OnPush,
                })
                export class %sListComponent {
                  readonly items = signal<%sItem[]>([
                    { id: '1', title: 'First %s Demo Item', status: 'ACTIVE', priority: 1 },
                    { id: '2', title: 'Second %s Verification', status: 'PENDING', priority: 2 }
                  ]);
                }
                """.formatted(
                entityName,
                lowerName,
                entityName,
                entityName.toUpperCase(Locale.ROOT),
                entityName,
                entityName,
                entityName,
                entityName
        )));
    }
}
