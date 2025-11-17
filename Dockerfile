FROM nginx:alpine

# Kopieer HTML bestanden naar nginx webroot
COPY html /usr/share/nginx/html

# Expose poort 80
EXPOSE 80

# Start nginx
CMD ["nginx", "-g", "daemon off;"]
