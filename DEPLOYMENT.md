# Deployment Instructies - SEMT Vatlas Webserver

Deze instructies helpen je om de HTML pagina's te serveren via een nginx webserver in Portainer.

## Optie 1: Deployment via Portainer met Docker Compose

### Stappen:

1. **Log in op Portainer**
   - Ga naar je Portainer interface

2. **Navigeer naar Stacks**
   - Klik op "Stacks" in het linker menu
   - Klik op "+ Add stack"

3. **Stack configureren**
   - **Name**: `semtvatlas-webserver`
   - **Build method**: Kies "Repository"
   - **Repository URL**: Vul je Git repository URL in
   - Of kies "Upload" en upload het `docker-compose.yml` bestand

4. **Deploy de stack**
   - Klik op "Deploy the stack"

5. **Toegang tot de webserver**
   - Open je browser en ga naar: `http://<ip-van-je-server>:8080/Kleedkameroverzicht.html`
   - Alle HTML pagina's zijn beschikbaar via: `http://<ip-van-je-server>:8080/<bestandsnaam>.html`

## Optie 2: Deployment via Portainer met Custom Container

### Stappen:

1. **Build de Docker image eerst (op je ontwikkelmachine)**
   ```bash
   docker build -t semtvatlas-webserver .
   ```

2. **Push naar Docker Hub (optioneel)**
   ```bash
   docker tag semtvatlas-webserver jouw-dockerhub-username/semtvatlas-webserver:latest
   docker push jouw-dockerhub-username/semtvatlas-webserver:latest
   ```

3. **In Portainer**
   - Ga naar "Containers"
   - Klik op "+ Add container"
   - **Name**: `semtvatlas-webserver`
   - **Image**: `jouw-dockerhub-username/semtvatlas-webserver:latest` (of een lokale image)
   - **Port mapping**:
     - Host: `8080`
     - Container: `80`
   - Klik op "Deploy the container"

## Optie 3: Lokaal testen (zonder Portainer)

```bash
# Build de image
docker build -t semtvatlas-webserver .

# Run de container
docker run -d -p 8080:80 --name semtvatlas-webserver semtvatlas-webserver

# Of gebruik docker-compose
docker-compose up -d
```

## Poort aanpassen

Als poort 8080 al in gebruik is, pas dan de `docker-compose.yml` aan:

```yaml
ports:
  - "3000:80"  # Verander 8080 naar een andere poort
```

## Beschikbare pagina's

Na deployment zijn de volgende pagina's beschikbaar:

- `http://<ip>:8080/Kleedkameroverzicht.html`
- `http://<ip>:8080/Kleedkamer01.html`
- `http://<ip>:8080/Kleedkamer02.html`
- etc.

## Troubleshooting

### Container start niet
- Check de logs in Portainer: Containers > semtvatlas-webserver > Logs

### Pagina niet bereikbaar
- Controleer of de poort open staat in je firewall
- Controleer of de container draait: `docker ps`
- Test lokaal eerst: `curl http://localhost:8080/Kleedkameroverzicht.html`

### Bestanden worden niet getoond
- Controleer of de `html` map bestaat in de build context
- Rebuild de image: `docker-compose up -d --build`
