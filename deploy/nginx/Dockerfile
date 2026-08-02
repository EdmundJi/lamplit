FROM nginx:1.27-alpine

RUN rm -rf /usr/share/nginx/html/*
COPY default.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/

EXPOSE 80
