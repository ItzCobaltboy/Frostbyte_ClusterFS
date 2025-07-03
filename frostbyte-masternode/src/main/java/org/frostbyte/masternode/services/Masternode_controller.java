package org.frostbyte.masternode.services;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Router {

    heartbeatRegister hr;

    @Autowired
    public Router(heartbeatRegister hr) {
        this.hr = hr;
    }
    @PostMapping('/')
}
