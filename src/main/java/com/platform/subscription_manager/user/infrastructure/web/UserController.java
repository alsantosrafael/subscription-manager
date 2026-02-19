package com.platform.subscription_manager.user.infrastructure.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/users")
public class UserController {

	@GetMapping()
	public String sayHello() {
		return "Hello!";
	}

}
